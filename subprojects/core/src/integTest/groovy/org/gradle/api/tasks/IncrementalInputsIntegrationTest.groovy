/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.api.tasks

import org.gradle.integtests.fixtures.ToBeFixedForConfigurationCache
import org.gradle.internal.execution.history.changes.ChangeTypeInternal
import org.gradle.work.Incremental
import spock.lang.Issue

class IncrementalInputsIntegrationTest extends AbstractIncrementalTasksIntegrationTest {

    String getTaskAction() {
        """
            void execute(InputChanges inputChanges) {
                assert !(inputChanges instanceof ExtensionAware)

                if (System.getProperty('forceFail')) {
                    throw new RuntimeException('failed')
                }

                incrementalExecution = inputChanges.incremental

                inputChanges.getFileChanges(inputDir).each { change ->
                    switch (change.changeType) {
                        case ChangeType.ADDED:
                            addedFiles << change.file
                            break
                        case ChangeType.MODIFIED:
                            modifiedFiles << change.file
                            break
                        case ChangeType.REMOVED:
                            removedFiles << change.file
                            break
                        default:
                            throw new IllegalStateException()
                    }
                }

                if (!inputChanges.incremental) {
                    createOutputsNonIncremental()
                }

                touchOutputs()
            }
        """
    }

    @Override
    ChangeTypeInternal getRebuildChangeType() {
        return ChangeTypeInternal.ADDED
    }

    @Override
    String getPrimaryInputAnnotation() {
        return "@${Incremental.simpleName}"
    }

    def "incremental task is executed non-incrementally when input file property has been added"() {
        given:
        file('new-input.txt').text = "new input file"
        previousExecution()

        when:
        buildFile << "incremental.inputs.file('new-input.txt')"

        then:
        executesNonIncrementally()
    }

    @Issue("https://github.com/gradle/gradle/issues/4166")
    @ToBeFixedForConfigurationCache(because = "task wrongly up-to-date")
    def "file in input dir appears in task inputs for #inputAnnotation"() {
        buildFile << """
            abstract class MyTask extends DefaultTask {
                @${inputAnnotation}
                @Incremental
                abstract DirectoryProperty getInput()
                @OutputFile
                File output

                @TaskAction
                void doStuff(InputChanges changes) {
                    def changed = changes.getFileChanges(input)*.file*.name as List
                    assert changed.contains('child')
                    output.text = changed.join('\\n')
                }
            }

            task myTask(type: MyTask) {
                input = mkdir(inputDir)
                output = file("build/output.txt")
            }
        """
        String myTask = ':myTask'

        when:
        file("inputDir1/child") << "inputFile1"
        run myTask, '-PinputDir=inputDir1'
        then:
        executedAndNotSkipped(myTask)

        when:
        file("inputDir2/child") << "inputFile2"
        run myTask, '-PinputDir=inputDir2'
        then:
        executedAndNotSkipped(myTask)

        where:
        inputAnnotation << [InputFiles.name, InputDirectory.name]
    }

    def "cannot query non-incremental file input parameters"() {
        given:
        buildFile << """
            abstract class WithNonIncrementalInput extends BaseIncrementalTask {

                @InputFile
                abstract RegularFileProperty getNonIncrementalInput()

                @Optional
                @OutputFile
                abstract RegularFileProperty getOutputFile()

                @Override
                void execute(InputChanges inputChanges) {
                    inputChanges.getFileChanges(nonIncrementalInput)
                }
            }

            task withNonIncrementalInput(type: WithNonIncrementalInput) {
                inputDir = file("inputs")
                nonIncrementalInput = file("nonIncremental")
            }
        """
        file("nonIncremental").text = "input"

        expect:
        fails("withNonIncrementalInput")
        failure.assertHasCause("Cannot query incremental changes: No property found for value task ':withNonIncrementalInput' property 'nonIncrementalInput'. Incremental properties: inputDir.")
    }

    def "changes to non-incremental input parameters cause a rebuild"() {
        given:
        buildFile << """
            abstract class WithNonIncrementalInput extends BaseIncrementalTask {

                @InputFile
                File nonIncrementalInput

                @Optional
                @OutputFile
                abstract RegularFileProperty getOutputFile()

                @Override
                void execute(InputChanges changes) {
                    super.execute(changes)
                    assert !changes.incremental
                }
            }

            task withNonIncrementalInput(type: WithNonIncrementalInput) {
                inputDir = file("inputs")
                nonIncrementalInput = file("nonIncremental")
            }
        """
        file("nonIncremental").text = "input"
        run("withNonIncrementalInput")

        when:
        file("inputs/new-input-file.txt") << "new file"
        file("nonIncremental").text = 'changed'
        then:
        succeeds("withNonIncrementalInput")
    }

    def "properties annotated with SkipWhenEmpty are incremental"() {
        setupTaskSources("@${SkipWhenEmpty.simpleName}")

        given:
        previousExecution()

        when:
        file('inputs/file1.txt') << "changed content"

        then:
        executesIncrementally(modified: ['file1.txt'])
    }

    def "two incremental inputs cannot have the same value"() {
        buildFile << """

            class MyTask extends DefaultTask {
                File inputOne
                File inputTwo

                @Incremental
                @InputDirectory
                File getInputOne() {
                    inputOne
                }

                @Incremental
                @InputDirectory
                File getInputTwo() {
                    inputTwo
                }

                @OutputDirectory
                File outputDirectory

                @TaskAction
                void run(InputChanges changes) {
                    new File(outputDirectory, "one.txt").text = changes.getFileChanges(inputOne)*.file*.name.join("\\n")
                    new File(outputDirectory, "two.txt").text = changes.getFileChanges(inputTwo)*.file*.name.join("\\n")
                }
            }

            task myTask(type: MyTask) {
                inputOne = file("input")
                inputTwo = file("input")
                outputDirectory = file("build/output")
            }
        """

        file("input").createDir()
        def inputPath = file('input').absolutePath

        expect:
        fails("myTask")
        failureHasCause("Multiple entries with same value: inputTwo=$inputPath and inputOne=$inputPath")
    }

    def "two incremental file properties can point to the same file"() {
        buildFile << """
            abstract class MyTask extends DefaultTask {
                @Incremental
                @InputDirectory
                abstract DirectoryProperty getInputOne()

                @Incremental
                @InputDirectory
                abstract DirectoryProperty getInputTwo()

                @OutputDirectory
                File outputDirectory

                @TaskAction
                void run(InputChanges changes) {
                    new File(outputDirectory, "one.txt").text = changes.getFileChanges(inputOne)*.file*.name.join("\\n")
                    new File(outputDirectory, "two.txt").text = changes.getFileChanges(inputTwo)*.file*.name.join("\\n")
                }
            }

            task myTask(type: MyTask) {
                inputOne = file("input")
                inputTwo = file("input")
                outputDirectory = file("build/output")
            }
        """

        file("input").createDir()

        expect:
        succeeds("myTask")
    }

    @ToBeFixedForConfigurationCache(because = "task wrongly up-to-date")
    def "empty providers can be queried for incremental changes"() {
        file("buildSrc").deleteDir()
        buildFile.text = """
            abstract class MyTask extends DefaultTask {
                @Incremental
                @Optional
                @InputFiles
                ${propertyDefinition}

                @OutputDirectory
                File outputDirectory

                @TaskAction
                void run(InputChanges changes) {
                    new File(outputDirectory, "output.txt").text = "Success"
                    changes.getFileChanges(input).each {
                        println "Changes > \$it"
                    }
                }
            }

            task myTask(type: MyTask) {
                outputDirectory = file("build/output")
                if (project.findProperty("inputFile")) {
                    input = file(project.property("inputFile"))
                }
            }
        """

        file("input").createDir()

        expect:
        succeeds("myTask")

        when:
        file("inputFile").createDir()
        file("inputFile/someContents.txt").text = "input"
        run("myTask", "-PinputFile=inputFile")

        then:
        output.contains("Changes > Input file ${file("inputFile").absolutePath} has been added.")
        output.contains("Changes > Input file ${file("inputFile/someContents.txt").absolutePath} has been added.")

        where:
        propertyDefinition << ["abstract DirectoryProperty getInput()", "abstract RegularFileProperty getInput()"]
    }

    def "provides normalized paths (#pathSensitivity)"() {
        buildFile << """
            abstract class MyCopy extends DefaultTask {
                @Incremental
                @PathSensitive(PathSensitivity.${pathSensitivity.name()})
                @InputDirectory
                abstract DirectoryProperty getInputDirectory()

                @OutputDirectory
                abstract DirectoryProperty getOutputDirectory()

                @TaskAction
                void copy(InputChanges changes) {
                    changes.getFileChanges(inputDirectory).each { change ->
                        File outputFile = new File(outputDirectory.get().asFile, change.normalizedPath)
                        if (change.changeType == ChangeType.REMOVED) {
                            outputFile.delete()
                        } else {
                            if (change.file.file) {
                                outputFile.parentFile.mkdirs()
                                outputFile.text = change.file.text
                            }
                        }
                    }
                }
            }

            task copy(type: MyCopy) {
                inputDirectory = file("input")
                outputDirectory = file("build/output")
            }
        """
        def toBeModifiedPath = "in/some/subdir/input1.txt"
        def toBeRemovedPath = "in/some/subdir/input2.txt"
        def toBeAddedPath = "in/some/other/subdir/other-input.txt"
        file("input/$toBeModifiedPath").text = "input to copy"
        file("input/${toBeRemovedPath}").text = "input to copy"

        when:
        run("copy")
        then:
        executedAndNotSkipped(":copy")
        file("build/output/${normalizedPaths.modified}").text == "input to copy"
        file("build/output/${normalizedPaths.removed}").text == "input to copy"

        when:
        file("input/${toBeAddedPath}").text = "other input"
        file("input/${toBeModifiedPath}").text = "modified"
        assert file("input/${toBeRemovedPath}").delete()
        run("copy")
        then:
        executedAndNotSkipped(":copy")
        file("build/output/${normalizedPaths.modified}").text == "modified"
        !file("build/output/${normalizedPaths.removed}").exists()
        file("build/output/${normalizedPaths.added}").text == "other input"

        where:
        pathSensitivity           | normalizedPaths
        PathSensitivity.RELATIVE  | [modified: "in/some/subdir/input1.txt", added: "in/some/other/subdir/other-input.txt", removed: "in/some/subdir/input2.txt"]
        PathSensitivity.NAME_ONLY | [modified: "input1.txt", added: "other-input.txt", removed: "input2.txt"]
    }

    def "provides the file type"() {
        file("buildSrc").deleteDir()
        buildFile.text = """
            abstract class MyCopy extends DefaultTask {
                @Incremental
                @PathSensitive(PathSensitivity.RELATIVE)
                @InputFiles
                abstract DirectoryProperty getInputDirectory()

                @OutputDirectory
                abstract DirectoryProperty getOutputDirectory()

                @Inject
                abstract FileSystemOperations getFs()

                @TaskAction
                void copy(InputChanges changes) {
                    if (!changes.incremental) {
                        println("Full rebuild - recreating output directory")
                        def outputDir = outputDirectory.get().asFile
                        fs.delete { delete(outputDir) }
                        outputDir.mkdirs()
                    }
                    changes.getFileChanges(inputDirectory).each { change ->
                        File outputFile = new File(outputDirectory.get().asFile, change.normalizedPath)
                        if (change.changeType == ChangeType.REMOVED) {
                            assert change.fileType == determineFileType(outputFile)
                            if (change.fileType == FileType.FILE) {
                                println "deleting \${outputFile}"
                                assert outputFile.delete()
                            }
                        } else {
                            assert change.fileType == determineFileType(change.file)
                            if (change.fileType == FileType.FILE) {
                                outputFile.parentFile.mkdirs()
                                outputFile.text = change.file.text
                            }
                        }
                    }
                }

                protected FileType determineFileType(File file) {
                    if (file.file) {
                        return FileType.FILE
                    }
                    if (file.directory) {
                        return FileType.DIRECTORY
                    }
                    return FileType.MISSING
                }
            }

            task copy(type: MyCopy) {
                inputDirectory = file("input")
                outputDirectory = file("build/output")
            }
        """
        def inputDir = file("input")
        def outputDir = file("build/output")
        inputDir.file("modified.txt").text = "input to copy"
        inputDir.file("subdir/removed.txt").text = "input to copy"

        when:
        run("copy")
        then:
        executedAndNotSkipped(":copy")
        outputDir.assertHasDescendants("modified.txt", "subdir/removed.txt")

        when:
        inputDir.file("added.txt").text = "other input"
        inputDir.file("modified.txt").text = "modified"
        assert inputDir.file("subdir/removed.txt").delete()
        assert inputDir.file("subdir").delete()
        run("copy")
        then:
        executedAndNotSkipped(":copy")
        outputDir.assertHasDescendants("modified.txt", "added.txt", "subdir")

        when:
        inputDir.forceDeleteDir()
        run("copy")
        then:
        executedAndNotSkipped(":copy")
        outputDir.assertHasDescendants("subdir")

        when:
        inputDir.file("modified.txt").text = "some input"
        run("copy")
        // force rebuild
        outputDir.file("modified.txt").text = "changed"
        inputDir.forceDeleteDir()
        run("copy")
        then:
        executedAndNotSkipped(":copy")
        outputDir.assertIsEmptyDir()
    }

    def "outputs are cleaned out on rebuild (output type: #type)"() {
        file("buildSrc").deleteDir()
        buildFile.text = """
            abstract class MyCopy extends DefaultTask {
                @Incremental
                @PathSensitive(PathSensitivity.RELATIVE)
                @InputDirectory
                abstract DirectoryProperty getInputDirectory()

                @InputFile
                abstract RegularFileProperty getNonIncrementalInput()

                @${annotation.simpleName}
                abstract ${type} getOutputDirectory()

                @TaskAction
                void copy(InputChanges changes) {
                    if (!changes.incremental) {
                        println("Rebuilding")
                    }
                    changes.getFileChanges(inputDirectory).each { change ->
                        File outputFile = new File(outputDirectory.${getter}, change.normalizedPath)
                        if (change.changeType != ChangeType.REMOVED
                            && change.file.file) {
                            outputFile.parentFile.mkdirs()
                            outputFile.text = change.file.text
                        }
                    }
                }
            }

            task copy(type: MyCopy) {
                inputDirectory = file("input")
                nonIncrementalInput = file("nonIncremental.txt")
                outputDirectory.${setter}(file("build/output"))
            }
        """
        def inputFilePath = "in/some/input.txt"
        def nonIncrementalInput = file("nonIncremental.txt")
        nonIncrementalInput.text = "original"
        file("input/${inputFilePath}").text = "input to copy"

        when:
        run("copy")
        then:
        executedAndNotSkipped(":copy")
        file("build/output/${inputFilePath}").isFile()

        when:
        assert file("input/${inputFilePath}").delete()
        nonIncrementalInput.text = "changed"
        run("copy")
        then:
        executedAndNotSkipped(":copy")
        file("build/output").assertIsEmptyDir()

        where:
        type                   | annotation      | getter         | setter
        'DirectoryProperty'    | OutputDirectory | 'get().asFile' | 'set'
        'ConfigurableFileTree' | OutputFiles     | 'dir'          | 'from'
    }
}
