/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.smoketests

import org.gradle.integtests.fixtures.UnsupportedWithConfigurationCache

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class AndroidSantaTrackerSmokeTest extends AbstractAndroidSantaTrackerSmokeTest {

    // TODO:configuration-cache remove once fixed upstream
    @Override
    protected int maxConfigurationCacheProblems() {
        return 150
    }

    @UnsupportedWithConfigurationCache(iterationMatchers = [AGP_4_0_ITERATION_MATCHER, AGP_4_1_ITERATION_MATCHER])
    def "check deprecation warnings produced by building Santa Tracker (agp=#agpVersion)"() {

        given:
        AGP_VERSIONS.assumeCurrentJavaVersionIsSupportedBy(agpVersion)

        and:
        def checkoutDir = temporaryFolder.createDir("checkout")
        setupCopyOfSantaTracker(checkoutDir)

        when:
        buildLocationMaybeExpectingWorkerExecutorDeprecation(checkoutDir, agpVersion)

        then:
        assertConfigurationCacheStateStored()

        where:
        agpVersion << TESTED_AGP_VERSIONS
    }

    @UnsupportedWithConfigurationCache(iterationMatchers = [AGP_4_0_ITERATION_MATCHER, AGP_4_1_ITERATION_MATCHER])
    def "incremental Java compilation works for Santa Tracker (agp=#agpVersion)"() {

        given:
        AGP_VERSIONS.assumeCurrentJavaVersionIsSupportedBy(agpVersion)

        and:
        def checkoutDir = temporaryFolder.createDir("checkout")
        setupCopyOfSantaTracker(checkoutDir)

        and:
        def pathToClass = "com/google/android/apps/santatracker/tracker/ui/BottomSheetBehavior"
        def fileToChange = checkoutDir.file("tracker/src/main/java/${pathToClass}.java")
        def compiledClassFile = checkoutDir.file("tracker/build/intermediates/javac/debug/classes/${pathToClass}.class")

        when:
        def result = buildLocationMaybeExpectingWorkerExecutorDeprecation(checkoutDir, agpVersion)
        def md5Before = compiledClassFile.md5Hash

        then:
        result.task(":tracker:compileDebugJavaWithJavac").outcome == SUCCESS
        assertConfigurationCacheStateStored()

        when:
        fileToChange.replace("computeCurrentVelocity(1000", "computeCurrentVelocity(2000")
        buildLocationMaybeExpectingWorkerExecutorDeprecation(checkoutDir, agpVersion)
        def md5After = compiledClassFile.md5Hash

        then:
        result.task(":tracker:compileDebugJavaWithJavac").outcome == SUCCESS
        assertConfigurationCacheStateLoaded()
        md5After != md5Before

        where:
        agpVersion << TESTED_AGP_VERSIONS
    }

    @UnsupportedWithConfigurationCache(iterationMatchers = [AGP_4_0_ITERATION_MATCHER, AGP_4_1_ITERATION_MATCHER, AGP_4_2_ITERATION_MATCHER])
    def "can lint Santa-Tracker (agp=#agpVersion)"() {

        given:
        AGP_VERSIONS.assumeCurrentJavaVersionIsSupportedBy(agpVersion)

        and:
        def checkoutDir = temporaryFolder.createDir("checkout")
        setupCopyOfSantaTracker(checkoutDir)

        when:
        def runner = runnerForLocationExpectingLintDeprecations(checkoutDir, true, agpVersion, "common:lintDebug", "playgames:lintDebug", "doodles-lib:lintDebug")
        // Use --continue so that a deterministic set of tasks runs when some tasks fail
        runner.withArguments(runner.arguments + "--continue")
        def result = runner.buildAndFail()

        then:
        assertConfigurationCacheStateStored()
        result.output.contains("Lint found errors in the project; aborting build.")

        when:
        runner = runnerForLocationExpectingLintDeprecations(checkoutDir, false, agpVersion, "common:lintDebug", "playgames:lintDebug", "doodles-lib:lintDebug")
        runner.withArguments(runner.arguments + "--continue")
        result = runner.buildAndFail()

        then:
        assertConfigurationCacheStateLoaded()
        result.output.contains("Lint found errors in the project; aborting build.")

        where:
        agpVersion << TESTED_AGP_VERSIONS
    }

    private SmokeTestGradleRunner runnerForLocationExpectingLintDeprecations(File location, boolean isCleanBuild, String agpVersion, String... tasks) {
        SmokeTestGradleRunner runner = isCleanBuild ? runnerForLocationMaybeExpectingWorkerExecutorDeprecation(location, agpVersion, tasks) : runnerForLocation(location, agpVersion, tasks)
        expectAgpFileTreeDeprecationWarnings(runner, "compileDebugAidl", "compileDebugRenderscript")
        if (agpVersion.startsWith("7.")) {
            expectAgpFileTreeDeprecationWarnings(runner, "stripDebugDebugSymbols", "bundleLibResDebug")
        }
        return runner
    }
}
