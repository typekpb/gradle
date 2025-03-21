/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache

import groovy.lang.Closure
import groovy.lang.GroovyObjectSupport
import groovy.lang.Script
import org.gradle.api.Action
import org.gradle.api.AntBuilder
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.PathValidation
import org.gradle.api.Project
import org.gradle.api.ProjectEvaluationListener
import org.gradle.api.Task
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.ArtifactHandler
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.DependencyLockingHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.component.SoftwareComponentContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DeleteSpec
import org.gradle.api.file.FileTree
import org.gradle.api.file.ProjectLayout
import org.gradle.api.internal.DynamicObjectAware
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.ProcessOperations
import org.gradle.api.internal.artifacts.configurations.DependencyMetaDataProvider
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.initialization.ClassLoaderScope
import org.gradle.api.internal.initialization.ScriptHandlerInternal
import org.gradle.api.internal.plugins.ExtensionContainerInternal
import org.gradle.api.internal.plugins.PluginManagerInternal
import org.gradle.api.internal.project.CrossProjectModelAccess
import org.gradle.api.internal.project.ProjectIdentifier
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.project.ProjectState
import org.gradle.api.internal.project.ProjectStateInternal
import org.gradle.api.internal.tasks.TaskContainerInternal
import org.gradle.api.logging.Logger
import org.gradle.api.logging.LoggingManager
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.Convention
import org.gradle.api.plugins.ObjectConfigurationAction
import org.gradle.api.plugins.PluginContainer
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.resources.ResourceHandler
import org.gradle.api.tasks.WorkResult
import org.gradle.configuration.ConfigurationTargetIdentifier
import org.gradle.configuration.internal.UserCodeApplicationContext
import org.gradle.configuration.project.ProjectConfigurationActionContainer
import org.gradle.configurationcache.extensions.capitalized
import org.gradle.configurationcache.extensions.uncheckedCast
import org.gradle.configurationcache.problems.ProblemsListener
import org.gradle.configurationcache.problems.PropertyProblem
import org.gradle.configurationcache.problems.StructuredMessage
import org.gradle.configurationcache.problems.location
import org.gradle.groovy.scripts.ScriptSource
import org.gradle.internal.logging.StandardOutputCapture
import org.gradle.internal.metaobject.BeanDynamicObject
import org.gradle.internal.metaobject.DynamicObject
import org.gradle.internal.model.ModelContainer
import org.gradle.internal.model.RuleBasedPluginListener
import org.gradle.internal.service.ServiceRegistry
import org.gradle.internal.service.scopes.ServiceRegistryFactory
import org.gradle.model.internal.registry.ModelRegistry
import org.gradle.normalization.InputNormalizationHandler
import org.gradle.normalization.internal.InputNormalizationHandlerInternal
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import org.gradle.util.Path
import org.gradle.util.internal.ConfigureUtil
import java.io.File
import java.net.URI
import java.util.concurrent.Callable


class ProblemReportingCrossProjectModelAccess(
    private val delegate: CrossProjectModelAccess,
    private val problems: ProblemsListener,
    private val userCodeContext: UserCodeApplicationContext
) : CrossProjectModelAccess {
    override fun findProject(referrer: ProjectInternal, relativeTo: ProjectInternal, path: String): ProjectInternal? {
        return delegate.findProject(referrer, relativeTo, path)?.wrap(referrer)
    }

    override fun getSubprojects(referrer: ProjectInternal, relativeTo: ProjectInternal): MutableSet<out ProjectInternal> {
        return delegate.getSubprojects(referrer, relativeTo).mapTo(LinkedHashSet()) { it.wrap(referrer) }
    }

    override fun getAllprojects(referrer: ProjectInternal, relativeTo: ProjectInternal): MutableSet<out ProjectInternal> {
        return delegate.getAllprojects(referrer, relativeTo).mapTo(LinkedHashSet()) { it.wrap(referrer) }
    }

    private
    fun ProjectInternal.wrap(referrer: ProjectInternal): ProjectInternal {
        return if (this == referrer) {
            this
        } else {
            ProblemReportingProject(this, referrer, problems, userCodeContext)
        }
    }

    private
    class ProblemReportingProject(
        val delegate: ProjectInternal,
        val referrer: ProjectInternal,
        val problems: ProblemsListener,
        val userCodeContext: UserCodeApplicationContext
    ) : ProjectInternal, GroovyObjectSupport() {

        override fun toString(): String {
            return delegate.toString()
        }

        override fun getProperty(propertyName: String): Any {
            // Attempt to get the property value via this instance. If not present, then attempt to lookup via the delegate
            val thisBean = BeanDynamicObject(this).withNotImplementsMissing()
            val result = thisBean.tryGetProperty(propertyName)
            if (result.isFound) {
                return result.value
            }
            val delegateBean = (delegate as DynamicObjectAware).asDynamicObject
            val delegateResult = delegateBean.tryGetProperty(propertyName)
            if (delegateResult.isFound) {
                // Only report properties that exist
                onAccess()
                return delegateResult.value
            }
            throw thisBean.getMissingProperty(propertyName)
        }

        override fun invokeMethod(name: String, args: Any): Any {
            // Attempt to get the property value via this instance. If not present, then attempt to lookup via the delegate
            val varargs: Array<Any?> = args.uncheckedCast()
            val thisBean = BeanDynamicObject(this).withNotImplementsMissing()
            val result = thisBean.tryInvokeMethod(name, *varargs)
            if (result.isFound) {
                return result.value
            }
            val delegateBean = (delegate as DynamicObjectAware).asDynamicObject
            val delegateResult = delegateBean.tryInvokeMethod(name, *varargs)
            if (delegateResult.isFound) {
                // Only report methods that exist
                onAccess()
                return delegateResult.value
            }
            throw thisBean.methodMissingException(name, args)
        }

        override fun compareTo(other: Project?): Int {
            return delegate.compareTo(other)
        }

        override fun getRootDir(): File {
            return delegate.rootDir
        }

        override fun getBuildDir(): File {
            onAccess()
            return delegate.buildDir
        }

        override fun setBuildDir(path: File) {
            onAccess()
            delegate.buildDir = path
        }

        override fun setBuildDir(path: Any) {
            onAccess()
            delegate.setBuildDir(path)
        }

        override fun getName(): String {
            return delegate.name
        }

        override fun getBuildFile(): File {
            onAccess()
            return delegate.buildFile
        }

        override fun getDisplayName(): String {
            return delegate.displayName
        }

        override fun getDescription(): String? {
            onAccess()
            return delegate.description
        }

        override fun setDescription(description: String?) {
            onAccess()
            delegate.description = description
        }

        override fun getGroup(): Any {
            onAccess()
            return delegate.group
        }

        override fun setGroup(group: Any) {
            onAccess()
            delegate.group = group
        }

        override fun getVersion(): Any {
            onAccess()
            return delegate.version
        }

        override fun setVersion(version: Any) {
            onAccess()
            delegate.version = version
        }

        override fun getStatus(): Any {
            onAccess()
            return delegate.status
        }

        override fun getInternalStatus(): Property<Any> {
            onAccess()
            return delegate.internalStatus
        }

        override fun setStatus(status: Any) {
            onAccess()
            delegate.status = status
        }

        override fun getChildProjects(): MutableMap<String, Project> {
            return delegate.childProjects
        }

        override fun setProperty(name: String, value: Any?) {
            onAccess()
            delegate.setProperty(name, value)
        }

        override fun getProject(): ProjectInternal {
            return this
        }

        override fun task(name: String): Task {
            onAccess()
            return delegate.task(name)
        }

        override fun task(args: MutableMap<String, *>, name: String): Task {
            onAccess()
            return delegate.task(args, name)
        }

        override fun task(args: MutableMap<String, *>, name: String, configureClosure: Closure<*>): Task {
            onAccess()
            return delegate.task(args, name, configureClosure)
        }

        override fun task(name: String, configureClosure: Closure<*>): Task {
            onAccess()
            return delegate.task(name, configureClosure)
        }

        override fun task(name: String, configureAction: Action<in Task>): Task {
            onAccess()
            return delegate.task(name, configureAction)
        }

        override fun getPath(): String {
            return delegate.path
        }

        override fun getDefaultTasks(): MutableList<String> {
            onAccess()
            return delegate.defaultTasks
        }

        override fun setDefaultTasks(defaultTasks: MutableList<String>) {
            onAccess()
            delegate.defaultTasks = defaultTasks
        }

        override fun defaultTasks(vararg defaultTasks: String?) {
            onAccess()
            delegate.defaultTasks(*defaultTasks)
        }

        override fun evaluationDependsOn(path: String): Project {
            onAccess()
            return delegate.evaluationDependsOn(path)
        }

        override fun evaluationDependsOnChildren() {
            onAccess()
            delegate.evaluationDependsOnChildren()
        }

        override fun getAllTasks(recursive: Boolean): MutableMap<Project, MutableSet<Task>> {
            onAccess()
            return delegate.getAllTasks(recursive)
        }

        override fun getTasksByName(name: String, recursive: Boolean): MutableSet<Task> {
            onAccess()
            return delegate.getTasksByName(name, recursive)
        }

        override fun getProjectDir(): File {
            return delegate.projectDir
        }

        override fun file(path: Any): File {
            onAccess()
            return delegate.file(path)
        }

        override fun file(path: Any, validation: PathValidation): File {
            onAccess()
            return delegate.file(path, validation)
        }

        override fun uri(path: Any): URI {
            onAccess()
            return delegate.uri(path)
        }

        override fun relativePath(path: Any): String {
            onAccess()
            return delegate.relativePath(path)
        }

        override fun files(vararg paths: Any?): ConfigurableFileCollection {
            onAccess()
            return delegate.files(*paths)
        }

        override fun files(paths: Any, configureClosure: Closure<*>): ConfigurableFileCollection {
            onAccess()
            return delegate.files(paths, configureClosure)
        }

        override fun files(paths: Any, configureAction: Action<in ConfigurableFileCollection>): ConfigurableFileCollection {
            onAccess()
            return delegate.files(paths, configureAction)
        }

        override fun fileTree(baseDir: Any): ConfigurableFileTree {
            onAccess()
            return delegate.fileTree(baseDir)
        }

        override fun fileTree(baseDir: Any, configureClosure: Closure<*>): ConfigurableFileTree {
            onAccess()
            return delegate.fileTree(baseDir, configureClosure)
        }

        override fun fileTree(baseDir: Any, configureAction: Action<in ConfigurableFileTree>): ConfigurableFileTree {
            onAccess()
            return delegate.fileTree(baseDir, configureAction)
        }

        override fun fileTree(args: MutableMap<String, *>): ConfigurableFileTree {
            onAccess()
            return delegate.fileTree(args)
        }

        override fun zipTree(zipPath: Any): FileTree {
            onAccess()
            return delegate.zipTree(zipPath)
        }

        override fun tarTree(tarPath: Any): FileTree {
            onAccess()
            return delegate.tarTree(tarPath)
        }

        override fun <T : Any?> provider(value: Callable<T>): Provider<T> {
            onAccess()
            return delegate.provider(value)
        }

        override fun getProviders(): ProviderFactory {
            onAccess()
            return delegate.providers
        }

        override fun getObjects(): ObjectFactory {
            onAccess()
            return delegate.objects
        }

        override fun getLayout(): ProjectLayout {
            onAccess()
            return delegate.layout
        }

        override fun mkdir(path: Any): File {
            onAccess()
            return delegate.mkdir(path)
        }

        override fun delete(vararg paths: Any?): Boolean {
            onAccess()
            return delegate.delete(*paths)
        }

        override fun delete(action: Action<in DeleteSpec>): WorkResult {
            onAccess()
            return delegate.delete(action)
        }

        override fun javaexec(closure: Closure<*>): ExecResult {
            onAccess()
            return delegate.javaexec(closure)
        }

        override fun javaexec(action: Action<in JavaExecSpec>): ExecResult {
            onAccess()
            return delegate.javaexec(action)
        }

        override fun exec(closure: Closure<*>): ExecResult {
            onAccess()
            return delegate.exec(closure)
        }

        override fun exec(action: Action<in ExecSpec>): ExecResult {
            onAccess()
            return delegate.exec(action)
        }

        override fun absoluteProjectPath(path: String): String {
            return delegate.absoluteProjectPath(path)
        }

        override fun relativeProjectPath(path: String): String {
            return delegate.relativeProjectPath(path)
        }

        override fun getAnt(): AntBuilder {
            onAccess()
            return delegate.ant
        }

        override fun createAntBuilder(): AntBuilder {
            onAccess()
            return delegate.createAntBuilder()
        }

        override fun ant(configureClosure: Closure<*>): AntBuilder {
            onAccess()
            return delegate.ant(configureClosure)
        }

        override fun ant(configureAction: Action<in AntBuilder>): AntBuilder {
            onAccess()
            return delegate.ant(configureAction)
        }

        override fun getConfigurations(): ConfigurationContainer {
            onAccess()
            return delegate.configurations
        }

        override fun configurations(configureClosure: Closure<*>) {
            onAccess()
            delegate.configurations(configureClosure)
        }

        override fun getArtifacts(): ArtifactHandler {
            onAccess()
            return delegate.artifacts
        }

        override fun artifacts(configureClosure: Closure<*>) {
            onAccess()
            delegate.artifacts(configureClosure)
        }

        override fun artifacts(configureAction: Action<in ArtifactHandler>) {
            onAccess()
            delegate.artifacts(configureAction)
        }

        @Deprecated("The concept of conventions is deprecated. Use extensions instead.")
        override fun getConvention(): Convention {
            onAccess()
            @Suppress("deprecation")
            return delegate.convention
        }

        override fun depthCompare(otherProject: Project): Int {
            return delegate.depthCompare(otherProject)
        }

        override fun getDepth(): Int {
            return delegate.depth
        }

        override fun project(path: String, configureClosure: Closure<*>): Project {
            return project(path, ConfigureUtil.configureUsing(configureClosure))
        }

        override fun project(path: String, configureAction: Action<in Project>): Project {
            return delegate.project(referrer, path, configureAction)
        }

        override fun project(referrer: ProjectInternal, path: String, configureAction: Action<in Project>): ProjectInternal {
            return delegate.project(referrer, path, configureAction)
        }

        override fun getSubprojects(): Set<Project> {
            return delegate.getSubprojects(referrer)
        }

        override fun getSubprojects(referrer: ProjectInternal): Set<ProjectInternal> {
            return delegate.getSubprojects(referrer)
        }

        override fun subprojects(action: Action<in Project>) {
            delegate.subprojects(referrer, action)
        }

        override fun subprojects(configureClosure: Closure<*>) {
            delegate.subprojects(referrer, ConfigureUtil.configureUsing(configureClosure))
        }

        override fun subprojects(referrer: ProjectInternal, configureAction: Action<in Project>) {
            delegate.subprojects(referrer, configureAction)
        }

        override fun getAllprojects(): Set<Project> {
            return delegate.getAllprojects(referrer)
        }

        override fun getAllprojects(referrer: ProjectInternal): Set<ProjectInternal> {
            return delegate.getAllprojects(referrer)
        }

        override fun allprojects(action: Action<in Project>) {
            delegate.allprojects(referrer, action)
        }

        override fun allprojects(configureClosure: Closure<*>) {
            delegate.allprojects(referrer, ConfigureUtil.configureUsing(configureClosure))
        }

        override fun allprojects(referrer: ProjectInternal, configureAction: Action<in Project>) {
            delegate.allprojects(referrer, configureAction)
        }

        override fun beforeEvaluate(action: Action<in Project>) {
            onAccess()
            delegate.beforeEvaluate(action)
        }

        override fun afterEvaluate(action: Action<in Project>) {
            onAccess()
            delegate.afterEvaluate(action)
        }

        override fun beforeEvaluate(closure: Closure<*>) {
            onAccess()
            delegate.beforeEvaluate(closure)
        }

        override fun afterEvaluate(closure: Closure<*>) {
            onAccess()
            delegate.afterEvaluate(closure)
        }

        override fun hasProperty(propertyName: String): Boolean {
            onAccess()
            return delegate.hasProperty(propertyName)
        }

        override fun getProperties(): MutableMap<String, *> {
            onAccess()
            return delegate.properties
        }

        override fun property(propertyName: String): Any? {
            onAccess()
            return delegate.property(propertyName)
        }

        override fun findProperty(propertyName: String): Any? {
            onAccess()
            return delegate.findProperty(propertyName)
        }

        override fun getLogger(): Logger {
            onAccess()
            return delegate.logger
        }

        override fun getLogging(): LoggingManager {
            onAccess()
            return delegate.logging
        }

        override fun configure(target: Any, configureClosure: Closure<*>): Any {
            onAccess()
            return delegate.configure(target, configureClosure)
        }

        override fun configure(targets: MutableIterable<*>, configureClosure: Closure<*>): MutableIterable<*> {
            onAccess()
            return delegate.configure(targets, configureClosure)
        }

        override fun <T : Any?> configure(targets: MutableIterable<T>, configureAction: Action<in T>): MutableIterable<T> {
            onAccess()
            return delegate.configure(targets, configureAction)
        }

        override fun getRepositories(): RepositoryHandler {
            onAccess()
            return delegate.repositories
        }

        override fun repositories(configureClosure: Closure<*>) {
            onAccess()
            delegate.repositories(configureClosure)
        }

        override fun getDependencies(): DependencyHandler {
            onAccess()
            return delegate.dependencies
        }

        override fun dependencies(configureClosure: Closure<*>) {
            onAccess()
            delegate.dependencies(configureClosure)
        }

        override fun buildscript(configureClosure: Closure<*>) {
            onAccess()
            delegate.buildscript(configureClosure)
        }

        override fun copy(closure: Closure<*>): WorkResult {
            onAccess()
            return delegate.copy(closure)
        }

        override fun copy(action: Action<in CopySpec>): WorkResult {
            onAccess()
            return delegate.copy(action)
        }

        override fun copySpec(closure: Closure<*>): CopySpec {
            onAccess()
            return delegate.copySpec(closure)
        }

        override fun copySpec(action: Action<in CopySpec>): CopySpec {
            onAccess()
            return delegate.copySpec(action)
        }

        override fun copySpec(): CopySpec {
            onAccess()
            return delegate.copySpec()
        }

        override fun sync(action: Action<in CopySpec>): WorkResult {
            onAccess()
            return delegate.sync(action)
        }

        override fun <T : Any?> container(type: Class<T>): NamedDomainObjectContainer<T> {
            onAccess()
            return delegate.container(type)
        }

        override fun <T : Any?> container(type: Class<T>, factory: NamedDomainObjectFactory<T>): NamedDomainObjectContainer<T> {
            onAccess()
            return delegate.container(type, factory)
        }

        override fun <T : Any?> container(type: Class<T>, factoryClosure: Closure<*>): NamedDomainObjectContainer<T> {
            onAccess()
            return delegate.container(type, factoryClosure)
        }

        override fun getResources(): ResourceHandler {
            onAccess()
            return delegate.resources
        }

        override fun getComponents(): SoftwareComponentContainer {
            onAccess()
            return delegate.components
        }

        override fun getNormalization(): InputNormalizationHandlerInternal {
            onAccess()
            return delegate.normalization
        }

        override fun normalization(configuration: Action<in InputNormalizationHandler>) {
            onAccess()
            delegate.normalization(configuration)
        }

        override fun dependencyLocking(configuration: Action<in DependencyLockingHandler>) {
            onAccess()
            delegate.dependencyLocking(configuration)
        }

        override fun getDependencyLocking(): DependencyLockingHandler {
            onAccess()
            return delegate.dependencyLocking
        }

        override fun getPlugins(): PluginContainer {
            onAccess()
            return delegate.plugins
        }

        override fun apply(closure: Closure<*>) {
            onAccess()
            delegate.apply(closure)
        }

        override fun apply(action: Action<in ObjectConfigurationAction>) {
            onAccess()
            delegate.apply(action)
        }

        override fun apply(options: MutableMap<String, *>) {
            onAccess()
            delegate.apply(options)
        }

        override fun getPluginManager(): PluginManagerInternal {
            onAccess()
            return delegate.pluginManager
        }

        override fun identityPath(name: String): Path {
            shouldNotBeUsed()
        }

        override fun projectPath(name: String): Path {
            shouldNotBeUsed()
        }

        override fun getModel(): ModelContainer<*> {
            shouldNotBeUsed()
        }

        override fun getBuildPath(): Path {
            shouldNotBeUsed()
        }

        override fun isScript(): Boolean {
            shouldNotBeUsed()
        }

        override fun isRootScript(): Boolean {
            shouldNotBeUsed()
        }

        override fun isPluginContext(): Boolean {
            shouldNotBeUsed()
        }

        override fun getDependencyMetaDataProvider(): DependencyMetaDataProvider {
            shouldNotBeUsed()
        }

        override fun getFileOperations(): FileOperations {
            shouldNotBeUsed()
        }

        override fun getProcessOperations(): ProcessOperations {
            shouldNotBeUsed()
        }

        override fun getConfigurationTargetIdentifier(): ConfigurationTargetIdentifier {
            shouldNotBeUsed()
        }

        override fun getParentIdentifier(): ProjectIdentifier {
            shouldNotBeUsed()
        }

        override fun getParent(): ProjectInternal? {
            return delegate.parent
        }

        override fun getRootProject(): ProjectInternal {
            return delegate.rootProject
        }

        override fun evaluate(): Project {
            shouldNotBeUsed()
        }

        override fun bindAllModelRules(): ProjectInternal {
            shouldNotBeUsed()
        }

        override fun getTasks(): TaskContainerInternal {
            onAccess()
            return delegate.tasks
        }

        override fun getBuildScriptSource(): ScriptSource {
            shouldNotBeUsed()
        }

        override fun addChildProject(childProject: ProjectInternal) {
            shouldNotBeUsed()
        }

        override fun project(path: String): ProjectInternal {
            return delegate.project(referrer, path)
        }

        override fun project(referrer: ProjectInternal, path: String): ProjectInternal {
            return delegate.project(referrer, path)
        }

        override fun findProject(path: String): ProjectInternal? {
            return delegate.findProject(referrer, path)
        }

        override fun findProject(referrer: ProjectInternal, path: String): ProjectInternal? {
            return delegate.findProject(referrer, path)
        }

        override fun getInheritedScope(): DynamicObject {
            shouldNotBeUsed()
        }

        override fun getGradle(): GradleInternal {
            onAccess()
            return delegate.gradle
        }

        override fun getProjectEvaluationBroadcaster(): ProjectEvaluationListener {
            shouldNotBeUsed()
        }

        override fun addRuleBasedPluginListener(listener: RuleBasedPluginListener) {
            shouldNotBeUsed()
        }

        override fun prepareForRuleBasedPlugins() {
            shouldNotBeUsed()
        }

        override fun getFileResolver(): FileResolver {
            shouldNotBeUsed()
        }

        override fun getServices(): ServiceRegistry {
            shouldNotBeUsed()
        }

        override fun getServiceRegistryFactory(): ServiceRegistryFactory {
            shouldNotBeUsed()
        }

        override fun getStandardOutputCapture(): StandardOutputCapture {
            shouldNotBeUsed()
        }

        override fun getState(): ProjectStateInternal {
            onAccess()
            return delegate.state
        }

        override fun getExtensions(): ExtensionContainerInternal {
            onAccess()
            return delegate.extensions
        }

        override fun getConfigurationActions(): ProjectConfigurationActionContainer {
            shouldNotBeUsed()
        }

        override fun getModelRegistry(): ModelRegistry {
            shouldNotBeUsed()
        }

        override fun getClassLoaderScope(): ClassLoaderScope {
            shouldNotBeUsed()
        }

        override fun getBaseClassLoaderScope(): ClassLoaderScope {
            shouldNotBeUsed()
        }

        override fun setScript(script: Script) {
            shouldNotBeUsed()
        }

        override fun addDeferredConfiguration(configuration: Runnable) {
            shouldNotBeUsed()
        }

        override fun fireDeferredConfiguration() {
            shouldNotBeUsed()
        }

        override fun getProjectPath(): Path {
            return delegate.projectPath
        }

        override fun getIdentityPath(): Path {
            return delegate.identityPath
        }

        override fun stepEvaluationListener(listener: ProjectEvaluationListener, action: Action<ProjectEvaluationListener>): ProjectEvaluationListener? {
            shouldNotBeUsed()
        }

        override fun getOwner(): ProjectState {
            return delegate.owner
        }

        override fun getBuildscript(): ScriptHandlerInternal {
            onAccess()
            return delegate.buildscript
        }

        override fun newDetachedResolver(): ProjectInternal.DetachedResolver {
            shouldNotBeUsed()
        }

        fun shouldNotBeUsed(): Nothing {
            throw UnsupportedOperationException("This internal method should not be used.")
        }

        private
        fun onAccess() {
            val location = userCodeContext.location(null)
            val message = StructuredMessage.build {
                text("Cannot access project ")
                reference(delegate.identityPath.toString())
                text(" from project ")
                reference(referrer.identityPath.toString())
            }
            val exception = InvalidUserCodeException(message.toString().capitalized())
            problems.onProblem(
                PropertyProblem(location, message, exception, null)
            )
        }
    }
}
