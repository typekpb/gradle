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

package org.gradle.plugin.management.internal.autoapply;

import org.gradle.plugin.use.PluginId;
import org.gradle.plugin.use.internal.DefaultPluginId;

/**
 * Auto-applied Gradle Enterprise plugin information.
 *
 * Required by the {@code kotlin-dsl} module.
 */
public final class AutoAppliedGradleEnterprisePlugin {

    public static final String GROUP = "com.gradle";
    public static final String NAME = "gradle-enterprise-gradle-plugin";
    public static final String VERSION = "3.7.1";

    public static final PluginId ID = new DefaultPluginId("com.gradle.enterprise");
    public static final PluginId BUILD_SCAN_PLUGIN_ID = new DefaultPluginId("com.gradle.build-scan");

}
