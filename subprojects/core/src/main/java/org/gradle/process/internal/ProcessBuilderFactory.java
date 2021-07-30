/*
 * Copyright 2009 the original author or authors.
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

package org.gradle.process.internal;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates a {@link java.lang.ProcessBuilder} based on a {@link ExecHandle}.
 */
public class ProcessBuilderFactory {
    private static final Logger LOGGER = Logging.getLogger(DefaultExecHandle.class);
    public ProcessBuilder createProcessBuilder(ProcessSettings processSettings) {
        List<String> arguments = processSettings.getArguments();
        File directory = processSettings.getDirectory();
        Map<String,String> environment = processSettings.getEnvironment();
        if (arguments.get(arguments.size() - 1).endsWith("sum.s")) {
            LOGGER.info("ENV: {}", environment);
            File sumFile = new File(arguments.get(arguments.size() - 1));
            LOGGER.info("Check sum.s: {}, exist: {}, isFile: {}", arguments.get(arguments.size() - 1), sumFile.exists(), sumFile.isFile());
            LOGGER.info("workingdir: {}, exist: {}, isDir: {}", directory.getAbsolutePath(), directory.exists(), directory.isDirectory());
            arguments.forEach(arg -> {
                int index = arg.indexOf("C:\\");
                if (index != -1) {
                    String filePath = arg.substring(index);
                    File f = new File(filePath);
                    File p = f.getParentFile();
                    LOGGER.info("Check {}, exist: {}, isFile: {}, isDir: {}", filePath, f.exists(), f.isFile(), f.isDirectory());
                    LOGGER.info("Check {}, exist: {}, isFile: {}, isDir: {}", p.getAbsolutePath(), p.exists(), p.isFile(), p.isDirectory());
                }
            });
//            try {
//                List<String> lines = Files.readAllLines(sumFile.toPath());
//                lines = new ArrayList<>(lines);
//                lines.add("");
//                Files.write(sumFile.toPath(), lines);
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
        }

        List<String> commandWithArguments = new ArrayList<String>();
        commandWithArguments.add(processSettings.getCommand());
        commandWithArguments.addAll(processSettings.getArguments());

        ProcessBuilder processBuilder = new ProcessBuilder(commandWithArguments);
        processBuilder.directory(processSettings.getDirectory());
        processBuilder.redirectErrorStream(processSettings.getRedirectErrorStream());

        environment.clear();
        environment.putAll(processSettings.getEnvironment());

        return processBuilder;
    }
}
