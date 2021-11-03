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

import java.util.function.Consumer

class AccessTrackingSetTest extends Specification {
    private final Consumer<Object> consumer = Mock()
    private final Runnable aggregatingAccess = Mock()
    private final Set<String> inner = new HashSet<>(Arrays.asList('existing', 'other'))
    private final AccessTrackingSet<String> set = new AccessTrackingSet<>(inner, consumer, aggregatingAccess)

    def "contains of existing element is tracked"() {
        when:
        def result = set.contains('existing')

        then:
        result
        1 * consumer.accept('existing')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "contains of null is tracked"() {
        when:
        def result = set.contains(null)

        then:
        !result
        1 * consumer.accept(null)
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "contains of missing element is tracked"() {
        when:
        def result = set.contains('missing')

        then:
        !result
        1 * consumer.accept('missing')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "contains of inconvertible element is tracked"() {
        when:
        def result = set.contains(123)

        then:
        !result
        1 * consumer.accept(123)
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "containsAll of existing elements is tracked"() {
        when:
        def result = set.containsAll(Arrays.asList('existing', 'other'))

        then:
        result
        1 * consumer.accept('existing')
        1 * consumer.accept('other')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "containsAll of missing elements is tracked"() {
        when:
        def result = set.containsAll(Arrays.asList('missing', 'alsoMissing'))

        then:
        !result
        1 * consumer.accept('missing')
        1 * consumer.accept('alsoMissing')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "containsAll of missing and existing elements is tracked"() {
        when:
        def result = set.containsAll(Arrays.asList('missing', 'existing'))

        then:
        !result
        1 * consumer.accept('missing')
        1 * consumer.accept('existing')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "remove of existing element is tracked"() {
        when:
        def result = set.remove('existing')

        then:
        result
        1 * consumer.accept('existing')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "remove of missing element is tracked"() {
        when:
        def result = set.remove('missing')

        then:
        !result
        1 * consumer.accept('missing')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "removeAll of existing elements is tracked"() {
        when:
        def result = set.removeAll('existing', 'other')

        then:
        result
        1 * consumer.accept('existing')
        1 * consumer.accept('other')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "removeAll of missing elements is tracked"() {
        when:
        def result = set.removeAll('missing', 'alsoMissing')

        then:
        !result
        1 * consumer.accept('missing')
        1 * consumer.accept('alsoMissing')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "removeAll of existing and missing elements is tracked"() {
        when:
        def result = set.removeAll('existing', 'missing')

        then:
        result
        1 * consumer.accept('existing')
        1 * consumer.accept('missing')
        0 * consumer._
        0 * aggregatingAccess._
    }

    def "method #methodName is reported as aggregating"() {
        when:
        operation.accept(set)

        then:
        0 * consumer._
        (1.._) * aggregatingAccess.run()

        where:
        methodName            | operation
        "iterator()"          | op(Set::iterator)
        "toArray()"           | op(s -> s.toArray())
        "toArray()"           | op(s -> s.toArray())
        "toArray(T[])"        | op(s -> s.toArray(new String[0]))
        "forEach()"           | op(s -> s.forEach(e -> { }))
        "size()"              | op(Set::size)
        "isEmpty()"           | op(Set::isEmpty)
        "hashCode()"          | op(Set::hashCode)
        "equals(Object)"      | op(s -> Objects.equals(s, Collections.emptySet()))
        "removeIf(Predicate)" | op(s -> s.removeIf(e -> false))
    }

    def "method #methodName is not reported as aggregating"() {
        when:
        operation.accept(set)

        then:
        0 * consumer._
        0 * aggregatingAccess.run()

        where:
        methodName    | operation
        "retainAll()" | op(s -> s.retainAll(Collections.emptySet()))
        "clear()"     | op(Set::clear)
        "toString()"  | op(s -> s.toString())  // Method reference doesn't work there for some reason
    }

    // Shortcut to have a typed lambda expression in where: block
    static Consumer<Set<String>> op(Consumer<Set<String>> consumer) {
        return consumer
    }
}
