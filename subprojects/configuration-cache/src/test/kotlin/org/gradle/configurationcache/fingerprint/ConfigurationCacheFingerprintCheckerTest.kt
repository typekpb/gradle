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

package org.gradle.configurationcache.fingerprint

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import org.gradle.api.Describable
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.configurationcache.CheckedFingerprint
import org.gradle.configurationcache.problems.PropertyProblem
import org.gradle.configurationcache.problems.PropertyTrace
import org.gradle.configurationcache.serialization.Codec
import org.gradle.configurationcache.serialization.IsolateOwner
import org.gradle.configurationcache.serialization.ReadContext
import org.gradle.configurationcache.serialization.ReadIdentities
import org.gradle.configurationcache.serialization.ReadIsolate
import org.gradle.configurationcache.serialization.Tracer
import org.gradle.configurationcache.serialization.WriteContext
import org.gradle.configurationcache.serialization.WriteIdentities
import org.gradle.configurationcache.serialization.WriteIsolate
import org.gradle.configurationcache.serialization.beans.BeanStateReader
import org.gradle.configurationcache.serialization.beans.BeanStateWriter
import org.gradle.configurationcache.serialization.runReadOperation
import org.gradle.configurationcache.serialization.runWriteOperation
import org.gradle.internal.Try
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream


class ConfigurationCacheFingerprintCheckerTest {

    @Test
    fun `first modified init script is reported`() {
        assertThat(
            invalidationReasonForInitScriptsChange(
                from = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1),
                    File("unchanged.gradle.kts") to HashCode.fromInt(1)
                ),
                to = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(2),
                    File("unchanged.gradle.kts") to HashCode.fromInt(1)
                )
            ),
            equalTo("init script 'init.gradle.kts' has changed")
        )
    }

    @Test
    fun `index of first modified init script is reported when file names differ`() {
        assertThat(
            invalidationReasonForInitScriptsChange(
                from = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1),
                    File("before.gradle.kts") to HashCode.fromInt(2),
                    File("other.gradle.kts") to HashCode.fromInt(3)
                ),
                to = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1),
                    File("after.gradle.kts") to HashCode.fromInt(42),
                    File("other.gradle.kts") to HashCode.fromInt(3)
                )
            ),
            equalTo("content of 2nd init script, 'after.gradle.kts', has changed")
        )
    }

    @Test
    fun `added init script is reported`() {
        assertThat(
            invalidationReasonForInitScriptsChange(
                from = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1)
                ),
                to = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1),
                    File("added.init.gradle.kts") to HashCode.fromInt(2)
                )
            ),
            equalTo("init script 'added.init.gradle.kts' has been added")
        )
    }

    @Test
    fun `added init scripts are reported`() {
        assertThat(
            invalidationReasonForInitScriptsChange(
                from = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1)
                ),
                to = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1),
                    File("added.init.gradle.kts") to HashCode.fromInt(2),
                    File("another.init.gradle.kts") to HashCode.fromInt(3)
                )
            ),
            equalTo("init script 'added.init.gradle.kts' and 1 more have been added")
        )
    }

    @Test
    fun `removed init script is reported`() {
        assertThat(
            invalidationReasonForInitScriptsChange(
                from = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1),
                    File("removed.init.gradle.kts") to HashCode.fromInt(2)
                ),
                to = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1)
                )
            ),
            equalTo("init script 'removed.init.gradle.kts' has been removed")
        )
    }

    @Test
    fun `removed init scripts are reported`() {
        assertThat(
            invalidationReasonForInitScriptsChange(
                from = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1),
                    File("removed.init.gradle.kts") to HashCode.fromInt(2),
                    File("another.init.gradle.kts") to HashCode.fromInt(3)
                ),
                to = listOf(
                    File("init.gradle.kts") to HashCode.fromInt(1)
                )
            ),
            equalTo("init script 'removed.init.gradle.kts' and 1 more have been removed")
        )
    }

    @Test
    fun `build script invalidation reason`() {
        val scriptFile = File("build.gradle.kts")
        assertThat(
            checkFingerprintGiven(
                mock {
                    on { hashCodeOf(scriptFile) } doReturn HashCode.fromInt(1)
                    on { displayNameOf(scriptFile) } doReturn "displayNameOf(scriptFile)"
                },
                ConfigurationCacheFingerprint.InputFile(
                    scriptFile,
                    HashCode.fromInt(2)
                )
            ),
            equalTo("file 'displayNameOf(scriptFile)' has changed")
        )
    }

    @Test
    fun `invalidation reason includes ValueSource description`() {

        // given:
        val describableValueSource = mock<ValueSource<Any, ValueSourceParameters>>(
            extraInterfaces = arrayOf(Describable::class)
        ) {
            on { (this as Describable).displayName } doReturn "my value source"
        }

        val obtainedValue = obtainedValueMock()

        // expect:
        assertThat(
            checkFingerprintGiven(
                mock {
                    on { instantiateValueSourceOf(obtainedValue) } doReturn describableValueSource
                },
                ConfigurationCacheFingerprint.ValueSource(obtainedValue)
            ),
            equalTo("my value source has changed")
        )
    }

    private
    fun invalidationReasonForInitScriptsChange(
        from: Iterable<Pair<File, HashCode?>>,
        to: List<Pair<File, HashCode?>>
    ): InvalidationReason? = to.toMap().let { toMap ->
        checkFingerprintGiven(
            mock {
                on { allInitScripts } doReturn toMap.keys.toList()
                on { hashCodeOf(any()) }.then { invocation ->
                    toMap[invocation.getArgument(0)]
                }
                on { displayNameOf(any()) }.then { invocation ->
                    invocation.getArgument<File>(0).name
                }
            },
            ConfigurationCacheFingerprint.InitScripts(
                from.map { (file, hash) -> ConfigurationCacheFingerprint.InputFile(file, hash) }
            )
        )
    }

    private
    fun checkFingerprintGiven(
        host: ConfigurationCacheFingerprintChecker.Host,
        fingerprint: ConfigurationCacheFingerprint
    ): InvalidationReason? {

        val readContext = recordWritingOf {
            write(fingerprint)
            write(null)
        }

        val checkedFingerprint = readContext.runReadOperation {
            ConfigurationCacheFingerprintChecker(host).run {
                checkFingerprint()
            }
        }
        return when (checkedFingerprint) {
            is CheckedFingerprint.Valid -> null
            is CheckedFingerprint.EntryInvalid -> checkedFingerprint.reason
            else -> throw IllegalArgumentException()
        }
    }

    private
    fun obtainedValueMock(): ObtainedValue = mock {
        on { value } doReturn Try.successful(42)
    }

    private
    fun recordWritingOf(writeOperation: suspend WriteContext.() -> Unit): PlaybackReadContext =
        RecordingWriteContext().apply {
            runWriteOperation(writeOperation)
        }.toReadContext()

    /**
     * A [WriteContext] implementation that avoids serialization altogether
     * and simply records the written values.
     *
     * The written state can then be read from the [ReadContext] returned
     * by [toReadContext].
     */
    class RecordingWriteContext : WriteContext {

        private
        val values = mutableListOf<Any?>()

        fun toReadContext() =
            PlaybackReadContext(values.toList())

        override fun writeSmallInt(value: Int) {
            values.add(value)
        }

        override suspend fun write(value: Any?) {
            values.add(value)
        }

        override val tracer: Tracer?
            get() = null

        override val sharedIdentities: WriteIdentities
            get() = undefined()

        override val isolate: WriteIsolate
            get() = undefined()

        override fun beanStateWriterFor(beanType: Class<*>): BeanStateWriter =
            undefined()

        override fun writeClass(type: Class<*>): Unit =
            undefined()

        override val logger: Logger
            get() = undefined()

        override var trace: PropertyTrace
            get() = undefined()
            set(_) {}

        override fun onProblem(problem: PropertyProblem): Unit =
            undefined()

        override fun push(codec: Codec<Any?>): Unit =
            undefined()

        override fun push(owner: IsolateOwner, codec: Codec<Any?>): Unit =
            undefined()

        override fun pop(): Unit =
            undefined()

        override fun writeNullableString(value: CharSequence?): Unit =
            undefined()

        override fun writeNullableSmallInt(value: Int?): Unit =
            undefined()

        override fun writeLong(value: Long): Unit =
            undefined()

        override fun writeString(value: CharSequence?): Unit =
            undefined()

        override fun writeBytes(bytes: ByteArray?): Unit =
            undefined()

        override fun writeBytes(bytes: ByteArray?, offset: Int, count: Int): Unit =
            undefined()

        override fun writeByte(value: Byte): Unit =
            undefined()

        override fun writeBinary(bytes: ByteArray?): Unit =
            undefined()

        override fun writeBinary(bytes: ByteArray?, offset: Int, count: Int): Unit =
            undefined()

        override fun writeSmallLong(value: Long): Unit =
            undefined()

        override fun writeBoolean(value: Boolean): Unit =
            undefined()

        override fun getOutputStream(): OutputStream =
            undefined()

        override fun encodeChunked(writeAction: Encoder.EncodeAction<Encoder>) =
            undefined()

        override fun writeInt(value: Int): Unit =
            undefined()
    }

    class PlaybackReadContext(values: Iterable<Any?>) : ReadContext {

        private
        val reader = values.iterator()

        override fun readSmallInt(): Int = next()

        override suspend fun read(): Any? = next()

        @Suppress("unchecked_cast")
        private
        fun <T : Any?> next(): T = reader.next() as T

        override val sharedIdentities: ReadIdentities
            get() = undefined()

        override val isolate: ReadIsolate
            get() = undefined()

        override val classLoader: ClassLoader
            get() = undefined()

        override fun beanStateReaderFor(beanType: Class<*>): BeanStateReader =
            undefined()

        override fun getProject(path: String): ProjectInternal =
            undefined()

        override var immediateMode: Boolean
            get() = undefined()
            set(_) {}

        override fun readClass(): Class<*> =
            undefined()

        override val logger: Logger
            get() = undefined()

        override var trace: PropertyTrace
            get() = undefined()
            set(_) {}

        override fun onProblem(problem: PropertyProblem): Unit =
            undefined()

        override fun push(codec: Codec<Any?>): Unit =
            undefined()

        override fun push(owner: IsolateOwner, codec: Codec<Any?>): Unit =
            undefined()

        override fun pop(): Unit =
            undefined()

        override fun readInt(): Int =
            undefined()

        override fun readNullableSmallInt(): Int? =
            undefined()

        override fun readNullableString(): String? =
            undefined()

        override fun readByte(): Byte =
            undefined()

        override fun readBinary(): ByteArray =
            undefined()

        override fun skipBytes(count: Long): Unit =
            undefined()

        override fun readLong(): Long =
            undefined()

        override fun readSmallLong(): Long =
            undefined()

        override fun getInputStream(): InputStream =
            undefined()

        override fun readString(): String =
            undefined()

        override fun readBoolean(): Boolean =
            undefined()

        override fun readBytes(buffer: ByteArray?): Unit =
            undefined()

        override fun readBytes(buffer: ByteArray?, offset: Int, count: Int): Unit =
            undefined()

        override fun <T : Any?> decodeChunked(decodeAction: Decoder.DecodeAction<Decoder, T>): T =
            undefined()

        override fun skipChunked() =
            undefined()
    }
}


private
fun undefined(): Nothing =
    TODO("test execution shouldn't get here")
