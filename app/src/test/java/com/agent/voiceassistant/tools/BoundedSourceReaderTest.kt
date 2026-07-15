package com.agent.voiceassistant.tools

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedSourceReaderTest {
    @Test
    fun `short response ends normally without EOF`() {
        val source = Buffer().writeUtf8("{\"ok\":true}")

        val result = BoundedSourceReader.read(source, 512 * 1024L)

        assertArrayEquals("{\"ok\":true}".toByteArray(), result.bytes)
        assertFalse(result.truncated)
    }

    @Test
    fun `empty response is valid`() {
        val result = BoundedSourceReader.read(Buffer(), 100)

        assertArrayEquals(ByteArray(0), result.bytes)
        assertFalse(result.truncated)
    }

    @Test
    fun `response over limit is truncated`() {
        val source = Buffer().write(ByteArray(101) { it.toByte() })

        val result = BoundedSourceReader.read(source, 100)

        assertArrayEquals(ByteArray(100) { it.toByte() }, result.bytes)
        assertTrue(result.truncated)
    }
}
