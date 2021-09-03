/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.execution.plan;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.TaskContainerInternal;
import org.gradle.api.internal.tasks.TaskPropertyUtils;
import org.gradle.api.internal.tasks.properties.DefaultTaskProperties;
import org.gradle.api.internal.tasks.properties.OutputFilePropertySpec;
import org.gradle.api.internal.tasks.properties.OutputFilePropertyType;
import org.gradle.api.internal.tasks.properties.OutputFilesCollector;
import org.gradle.api.internal.tasks.properties.OutputUnpacker;
import org.gradle.api.internal.tasks.properties.PropertyValue;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.internal.tasks.properties.PropertyWalker;
import org.gradle.api.internal.tasks.properties.TaskProperties;
import org.gradle.api.tasks.TaskExecutionException;
import org.gradle.api.tasks.TaskInstantiationException;
import org.gradle.internal.ImmutableActionSet;
import org.gradle.internal.execution.WorkValidationContext;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.internal.resources.ResourceLock;
import org.gradle.internal.service.ServiceRegistry;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * A {@link TaskNode} implementation for a task in the current build.
 */
public class LocalTaskNode extends TaskNode {
    private final TaskInternal task;
    private final WorkValidationContext validationContext;
    private ImmutableActionSet<Task> postAction = ImmutableActionSet.empty();
    private boolean isolated;
    private List<? extends ResourceLock> resourceLocks;
    private TaskProperties taskProperties;

    public LocalTaskNode(TaskInternal task, WorkValidationContext workValidationContext, int ordinal) {
        super(ordinal);
        this.task = task;
        this.validationContext = workValidationContext;
    }

    /**
     * Indicates that this task is isolated and so does not require the project lock in order to execute.
     */
    public void isolated() {
        isolated = true;
    }

    public WorkValidationContext getValidationContext() {
        return validationContext;
    }

    @Nullable
    @Override
    public ResourceLock getProjectToLock() {
        if (isolated) {
            return null;
        } else {
            // Running the task requires permission to execute against its containing project
            return ((ProjectInternal) task.getProject()).getOwner().getTaskExecutionLock();
        }
    }

    @Nullable
    @Override
    public ProjectInternal getOwningProject() {
        // Task requires its owning project's execution services
        return (ProjectInternal) task.getProject();
    }

    @Override
    public List<? extends ResourceLock> getResourcesToLock() {
        if (resourceLocks == null) {
            resourceLocks = task.getSharedResources();
        }
        return resourceLocks;
    }

    @Override
    public TaskInternal getTask() {
        return task;
    }

    @Override
    public Action<? super Task> getPostAction() {
        return postAction;
    }

    public TaskProperties getTaskProperties() {
        return taskProperties;
    }

    @Override
    public void appendPostAction(Action<? super Task> action) {
        postAction = postAction.add(action);
    }

    @Override
    public Throwable getNodeFailure() {
        return task.getState().getFailure();
    }

    @Override
    public void rethrowNodeFailure() {
        task.getState().rethrowFailure();
    }

    @Override
    public void prepareForExecution() {
        ((TaskContainerInternal) task.getProject().getTasks()).prepareForExecution(task);
    }

    @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
        for (Node targetNode : getDependencies(dependencyResolver)) {
            addDependencySuccessor(targetNode);
            if (targetNode instanceof TaskNode) {
                ((TaskNode) targetNode).maybeSetOrdinal(getOrdinal());
            }
            processHardSuccessor.execute(targetNode);
        }
        for (Node targetNode : getFinalizedBy(dependencyResolver)) {
            if (!(targetNode instanceof TaskNode)) {
                throw new IllegalStateException("Only tasks can be finalizers: " + targetNode);
            }
            addFinalizerNode((TaskNode) targetNode);
            processHardSuccessor.execute(targetNode);
        }
        for (Node targetNode : getMustRunAfter(dependencyResolver)) {
            addMustSuccessor((TaskNode) targetNode);
        }
        for (Node targetNode : getShouldRunAfter(dependencyResolver)) {
            addShouldSuccessor(targetNode);
        }
    }

    @Override
    public boolean requiresMonitoring() {
        return false;
    }

    private void addFinalizerNode(TaskNode finalizerNode) {
        addFinalizer(finalizerNode);
        if (!finalizerNode.isInKnownState()) {
            finalizerNode.mustNotRun();
        }
    }

    private Set<Node> getDependencies(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getTaskDependencies());
    }

    private Set<Node> getFinalizedBy(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getFinalizedBy());
    }

    private Set<Node> getMustRunAfter(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getMustRunAfter());
    }

    private Set<Node> getShouldRunAfter(TaskDependencyResolver dependencyResolver) {
        return dependencyResolver.resolveDependenciesFor(task, task.getShouldRunAfter());
    }

    @Override
    public int compareTo(Node other) {
        if (getClass() != other.getClass()) {
            return getClass().getName().compareTo(other.getClass().getName());
        }
        LocalTaskNode localTask = (LocalTaskNode) other;
        return task.compareTo(localTask.task);
    }

    @Override
    public String toString() {
        return task.getIdentityPath().toString();
    }

    private void addOutputFilesToMutations(Set<OutputFilePropertySpec> outputFilePropertySpecs) {
        final MutationInfo mutations = getMutationInfo();
        outputFilePropertySpecs.forEach(spec -> {
            File outputLocation = spec.getOutputFile();
            if (outputLocation != null) {
                mutations.outputPaths.add(outputLocation.getAbsolutePath());
            }
            mutations.hasOutputs = true;
        });
    }

    private void addLocalStateFilesToMutations(FileCollection localStateFiles) {
        final MutationInfo mutations = getMutationInfo();
        localStateFiles.forEach(file -> {
            mutations.outputPaths.add(file.getAbsolutePath());
            mutations.hasLocalState = true;
        });
    }

    private void addDestroyablesToMutations(FileCollection destroyables) {
        destroyables
            .forEach(file -> getMutationInfo().destroyablePaths.add(file.getAbsolutePath()));
    }

    @Override
    public void resolveMutations() {
        final LocalTaskNode taskNode = this;
        final TaskInternal task = getTask();
        final MutationInfo mutations = getMutationInfo();
        ProjectInternal project = (ProjectInternal) task.getProject();
        ServiceRegistry serviceRegistry = project.getServices();
        final FileCollectionFactory fileCollectionFactory = serviceRegistry.get(FileCollectionFactory.class);
        PropertyWalker propertyWalker = serviceRegistry.get(PropertyWalker.class);
        try {
            taskProperties = DefaultTaskProperties.resolve(propertyWalker, fileCollectionFactory, task);

            addOutputFilesToMutations(taskProperties.getOutputFileProperties());
            addLocalStateFilesToMutations(taskProperties.getLocalStateFiles());
            addDestroyablesToMutations(taskProperties.getDestroyableFiles());

            mutations.hasFileInputs = !taskProperties.getInputFileProperties().isEmpty();
        } catch (Exception e) {
            throw new TaskExecutionException(task, e);
        }

        mutations.finalized = true;

        if (!mutations.destroyablePaths.isEmpty()) {
            if (mutations.hasOutputs) {
                throw new IllegalStateException("Task " + taskNode + " has both outputs and destroyables defined.  A task can define either outputs or destroyables, but not both.");
            }
            if (mutations.hasFileInputs) {
                throw new IllegalStateException("Task " + taskNode + " has both inputs and destroyables defined.  A task can define either inputs or destroyables, but not both.");
            }
            if (mutations.hasLocalState) {
                throw new IllegalStateException("Task " + taskNode + " has both local state and destroyables defined.  A task can define either local state or destroyables, but not both.");
            }
        }
    }

    @Override
    public void resolveKnownOutputAndDestroyableMutations() {
        TaskInternal task = getTask();
        ProjectInternal project = (ProjectInternal) task.getProject();
        ServiceRegistry serviceRegistry = project.getServices();
        FileCollectionFactory fileCollectionFactory = serviceRegistry.get(FileCollectionFactory.class);
        PropertyWalker propertyWalker = serviceRegistry.get(PropertyWalker.class);
        try {
            OutputsAndDestroyablesCollector outputsAndDestroyablesCollector = new OutputsAndDestroyablesCollector(task.toString(), fileCollectionFactory);
            try {
                TaskPropertyUtils.visitProperties(propertyWalker, task, TypeValidationContext.NOOP, outputsAndDestroyablesCollector);
            } catch (Exception e) {
                throw new TaskExecutionException(task, e);
            }

            addOutputFilesToMutations(outputsAndDestroyablesCollector.getFileProperties());
            addLocalStateFilesToMutations(outputsAndDestroyablesCollector.getLocalStateFiles());
            addDestroyablesToMutations(outputsAndDestroyablesCollector.getDestroyables());
        } catch (Exception e) {
            // TODO is there a better exception to throw here?
            throw new TaskInstantiationException("Failed to query properties of task " + task, e);
        }
    }

    private static class OutputsAndDestroyablesCollector extends PropertyVisitor.Adapter {
        private final List<Object> localStateFiles = Lists.newArrayList();
        private final List<Object> destroyables = Lists.newArrayList();
        private final OutputFilesCollector outputFilesCollector = new OutputFilesCollector();
        private final OutputUnpacker outputUnpacker;
        private final FileCollectionFactory fileCollectionFactory;

        public OutputsAndDestroyablesCollector(String ownerDisplayName, FileCollectionFactory fileCollectionFactory) {
            this.fileCollectionFactory = fileCollectionFactory;
            this.outputUnpacker = new OutputUnpacker(
                ownerDisplayName,
                fileCollectionFactory,
                true,
                false,
                outputFilesCollector
            );
        }

        public ImmutableSortedSet<OutputFilePropertySpec> getFileProperties() {
            return outputFilesCollector.getFileProperties();
        }

        public FileCollection getLocalStateFiles() {
            return fileCollectionFactory.resolvingLeniently(localStateFiles);
        }

        public FileCollection getDestroyables() {
            return fileCollectionFactory.resolvingLeniently(destroyables);
        }

        @Override
        public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
            outputUnpacker.visitOutputFileProperty(propertyName, optional, value, filePropertyType);
        }

        @Override
        public void visitDestroyableProperty(Object value) {
            destroyables.add(value);
        }

        @Override
        public void visitLocalStateProperty(Object value) {
            localStateFiles.add(value);
        }
    }
}
