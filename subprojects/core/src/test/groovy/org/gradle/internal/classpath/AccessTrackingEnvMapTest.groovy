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

package org.gradle.internal.classpath

import spock.lang.Specification

import java.util.function.BiConsumer

class AccessTrackingEnvMapTest extends Specification {
    private final Map<String, String> inner = ['existing': 'existingValue', 'other': 'otherValue']

    def "access to existing element with get() is tracked"() {
        given:
        BiConsumer<String, String> consumer = Mock()
        AccessTrackingEnvMap trackingMap = new AccessTrackingEnvMap(inner, consumer)

        when:
        def result = trackingMap.get('existing')

        then:
        result == 'existingValue'
        1 * consumer.accept('existing', 'existingValue')
    }

    def "access to missing element with get() is tracked"() {
        given:
        BiConsumer<String, String> consumer = Mock()
        AccessTrackingEnvMap trackingMap = new AccessTrackingEnvMap(inner, consumer)

        when:
        def result = trackingMap.get('missing')

        then:
        result == null
        1 * consumer.accept('missing', null)
    }

    def "access to existing element with getOrDefault() is tracked"() {
        given:
        BiConsumer<String, String> consumer = Mock()
        AccessTrackingEnvMap trackingMap = new AccessTrackingEnvMap(inner, consumer)

        when:
        def result = trackingMap.getOrDefault('existing', 'defaultValue')

        then:
        result == 'existingValue'
        1 * consumer.accept('existing', 'existingValue')
    }

    def "access to missing element with getOrDefault() is tracked"() {
        given:
        BiConsumer<String, String> consumer = Mock()
        AccessTrackingEnvMap trackingMap = new AccessTrackingEnvMap(inner, consumer)

        when:
        def result = trackingMap.getOrDefault('missing', 'defaultValue')

        then:
        result == 'defaultValue'
        1 * consumer.accept('missing', null)
    }

    def "access to existing element with forEach() is tracked"() {
        given:
        BiConsumer<String, String> consumer = Mock()
        AccessTrackingEnvMap trackingMap = new AccessTrackingEnvMap(inner, consumer)

        when:
        trackingMap.forEach { k, v -> }

        then:
        1 * consumer.accept('existing', 'existingValue')
    }

    def "access to existing element with entrySet() is tracked"() {
        given:
        BiConsumer<String, String> consumer = Mock()
        AccessTrackingEnvMap trackingMap = new AccessTrackingEnvMap(inner, consumer)

        when:
        trackingMap.entrySet().toList()

        then:
        1 * consumer.accept('existing', 'existingValue')
    }
}
