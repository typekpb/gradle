/*
 * Copyright 2020 the original author or authors.
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

import org.gradle.api.internal.BuildDefinition
import org.gradle.cache.CacheBuilder
import org.gradle.cache.FileLockManager
import org.gradle.cache.PersistentCache
import org.gradle.cache.internal.CleanupActionFactory
import org.gradle.cache.internal.LeastRecentlyUsedCacheCleanup
import org.gradle.cache.internal.SingleDepthFilesFinder
import org.gradle.cache.internal.filelock.LockOptionsBuilder
import org.gradle.cache.internal.streams.DefaultValueStore
import org.gradle.cache.internal.streams.ValueStore
import org.gradle.cache.scopes.BuildTreeScopedCache
import org.gradle.configurationcache.extensions.toDefaultLowerCase
import org.gradle.configurationcache.extensions.unsafeLazy
import org.gradle.internal.Factory
import org.gradle.internal.concurrent.Stoppable
import org.gradle.internal.file.FileAccessTimeJournal
import org.gradle.internal.file.impl.SingleDepthFileAccessTracker
import org.gradle.internal.nativeintegration.filesystem.FileSystem
import org.gradle.internal.service.scopes.Scopes
import org.gradle.internal.service.scopes.ServiceScope
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption


@ServiceScope(Scopes.BuildTree::class)
internal
class ConfigurationCacheRepository(
    cacheRepository: BuildTreeScopedCache,
    cacheCleanupFactory: CleanupActionFactory,
    private val fileAccessTimeJournal: FileAccessTimeJournal,
    private val fileSystem: FileSystem
) : Stoppable {
    fun forKey(cacheKey: String): ConfigurationCacheStateStore {
        return StoreImpl(cache.baseDirFor(cacheKey))
    }

    abstract class Layout {
        abstract fun fileFor(stateType: StateType): ConfigurationCacheStateFile
    }

    private
    inner class WriteableLayout(
        private val cacheDir: File,
        private val onFileAccess: (File) -> Unit
    ) : Layout() {
        override fun fileFor(stateType: StateType): ConfigurationCacheStateFile = WriteableConfigurationCacheStateFile(cacheDir.stateFile(stateType), onFileAccess)
    }

    private
    inner class ReadableLayout(
        private val cacheDir: File
    ) : Layout() {
        override fun fileFor(stateType: StateType): ConfigurationCacheStateFile = ReadableConfigurationCacheStateFile(cacheDir.stateFile(stateType))
    }

    override fun stop() {
        cache.close()
    }

    private
    inner class ReadableConfigurationCacheStateFile(
        private val file: File
    ) : ConfigurationCacheStateFile {
        override val exists: Boolean
            get() = file.isFile

        override fun outputStream(): OutputStream =
            throw UnsupportedOperationException()

        override fun inputStream(): InputStream =
            file.also(::markAccessed).inputStream()

        override fun delete() {
            throw UnsupportedOperationException()
        }

        override fun moveFrom(file: File) {
            throw UnsupportedOperationException()
        }

        override fun stateFileForIncludedBuild(build: BuildDefinition): ConfigurationCacheStateFile =
            ReadableConfigurationCacheStateFile(
                includedBuildFileFor(file, build)
            )
    }

    private
    inner class WriteableConfigurationCacheStateFile(
        private val file: File,
        private val onFileAccess: (File) -> Unit
    ) : ConfigurationCacheStateFile {
        override val exists: Boolean
            get() = false

        override fun outputStream(): OutputStream =
            file.also(onFileAccess).outputStream()

        override fun inputStream(): InputStream =
            throw UnsupportedOperationException()

        override fun delete() {
            if (file.exists()) {
                Files.delete(file.toPath())
            }
        }

        override fun moveFrom(file: File) {
            Files.move(file.toPath(), this.file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }

        override fun stateFileForIncludedBuild(build: BuildDefinition): ConfigurationCacheStateFile =
            WriteableConfigurationCacheStateFile(
                includedBuildFileFor(file, build),
                onFileAccess
            )
    }

    private
    inner class StoreImpl(
        private val baseDir: File
    ) : ConfigurationCacheStateStore {
        override fun assignSpoolFile(stateType: StateType): File {
            Files.createDirectories(baseDir.toPath())
            return Files.createTempFile(baseDir.toPath(), stateType.fileBaseName, ".tmp").toFile()
        }

        override fun <T> createValueStore(stateType: StateType, writerFactory: (OutputStream) -> ValueStore.Writer<T>, readerFactory: (InputStream) -> ValueStore.Reader<T>): ValueStore<T> {
            return DefaultValueStore(baseDir, stateType.fileBaseName, writerFactory, readerFactory)
        }

        override fun <T : Any> useForStateLoad(stateType: StateType, action: (ConfigurationCacheStateFile) -> T): T {
            return useForStateLoad { layout -> action(layout.fileFor(stateType)) }
        }

        override fun <T : Any> useForStateLoad(action: (Layout) -> T): T {
            return withExclusiveAccessToCache(baseDir) { cacheDir ->
                action(ReadableLayout(cacheDir))
            }
        }

        override fun useForStore(action: (Layout) -> Unit) {
            withExclusiveAccessToCache(baseDir) { cacheDir ->
                // TODO GlobalCache require(!cacheDir.isDirectory)
                Files.createDirectories(cacheDir.toPath())
                chmod(cacheDir, 448) // octal 0700
                markAccessed(cacheDir)
                val stateFiles = mutableListOf<File>()
                val layout = WriteableLayout(cacheDir, stateFiles::add)
                try {
                    action(layout)
                } finally {
                    stateFiles.asSequence()
                        .filter(File::isFile)
                        .forEach {
                            chmod(it, 384) // octal 0600
                        }
                }
            }
        }
    }

    private
    fun includedBuildFileFor(parentStateFile: File, build: BuildDefinition) =
        parentStateFile.run {
            resolveSibling("$name.${build.name}")
        }

    private
    val cleanupDepth = 1

    private
    val cleanupMaxAgeDays = LeastRecentlyUsedCacheCleanup.DEFAULT_MAX_AGE_IN_DAYS_FOR_RECREATABLE_CACHE_ENTRIES

    private
    val cache = cacheRepository
        .crossVersionCache("configuration-cache")
        .withDisplayName("Configuration Cache")
        .withOnDemandLockMode() // Don't need to lock anything until we use the caches
        .withLruCacheCleanup(cacheCleanupFactory)
        .open()

    private
    fun CacheBuilder.withOnDemandLockMode() =
        withLockOptions(LockOptionsBuilder.mode(FileLockManager.LockMode.OnDemand))

    private
    fun CacheBuilder.withLruCacheCleanup(cleanupActionFactory: CleanupActionFactory): CacheBuilder =
        withCleanup(
            cleanupActionFactory.create(
                LeastRecentlyUsedCacheCleanup(
                    SingleDepthFilesFinder(cleanupDepth),
                    fileAccessTimeJournal,
                    cleanupMaxAgeDays
                )
            )
        )

    private
    val fileAccessTracker by unsafeLazy {
        SingleDepthFileAccessTracker(fileAccessTimeJournal, cache.baseDir, cleanupDepth)
    }

    private
    fun chmod(file: File, mode: Int) {
        fileSystem.chmod(file, mode)
    }

    private
    fun markAccessed(stateFile: File) {
        fileAccessTracker.markAccessed(stateFile)
    }

    private
    fun <T> withExclusiveAccessToCache(baseDir: File, action: (File) -> T): T =
        cache.withFileLock(
            Factory {
                action(baseDir)
            }
        )

    private
    fun PersistentCache.baseDirFor(cacheKey: String) =
        baseDir.resolve(cacheKey)

    private
    val StateType.fileBaseName: String
        get() = name.toDefaultLowerCase()

    private
    fun File.stateFile(stateType: StateType) = resolve("${stateType.fileBaseName}.bin")
}
