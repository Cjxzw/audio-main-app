package com.agent.voiceassistant.tools

import org.junit.Assert.*
import org.junit.Test

class BinaryFileCodecTest {
    @Test fun `binary codecs preserve all byte values and reject malformed input`() {
        val bytes = ByteArray(256) { it.toByte() }
        for (encoding in listOf("hex", "base64")) {
            assertArrayEquals(bytes, BinaryFileCodec.decode(BinaryFileCodec.encode(bytes, encoding), encoding))
            assertArrayEquals(byteArrayOf(), BinaryFileCodec.decode("", encoding))
        }
        for ((encoding, content) in listOf("hex" to "abc", "hex" to "gg", "base64" to "YWJj\n", "base64" to "AB==", "base64" to "YQ")) {
            assertThrows(IllegalArgumentException::class.java) { BinaryFileCodec.decode(content, encoding) }
        }
        assertThrows(IllegalStateException::class.java) { BinaryFileCodec.utf8(bytes) }
        val large = ByteArray(100000) { it.toByte() }
        assertArrayEquals(large, BinaryFileCodec.decode(BinaryFileCodec.encode(large, "base64"), "base64"))
        val text = "中文🙂\u0000"
        assertEquals(text, BinaryFileCodec.utf8(text.toByteArray()))
    }
}
