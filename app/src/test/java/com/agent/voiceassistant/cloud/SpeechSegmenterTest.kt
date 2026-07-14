package com.agent.voiceassistant.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    @Test
    fun `does not split decimal weather values`() {
        val segmenter = SpeechSegmenter(minHardChars = 4, softTargetChars = 30, maxChars = 120)
        val output = segmenter.feed("现在晴天，27.4度，体感30.5度。今天最高35度。").toMutableList()
        segmenter.flush()?.let { output += it }

        assertEquals(
            listOf("现在晴天，27.4度，体感30.5度。", "今天最高35度。"),
            output,
        )
    }

    @Test
    fun `does not split version or ip address`() {
        val segmenter = SpeechSegmenter(minHardChars = 4, softTargetChars = 40, maxChars = 120)
        val output = segmenter.feed("mimo-v2.5已经可用，设备地址是192.168.8.21。").toMutableList()
        segmenter.flush()?.let { output += it }

        assertEquals(listOf("mimo-v2.5已经可用，设备地址是192.168.8.21。"), output)
    }

    @Test
    fun `tool block is not exposed as speech`() {
        val extractor = StreamingSpeechExtractor()
        val speech = buildString {
            append(extractor.feed("<REPLY>我查一下。</REPLY>"))
            append(extractor.feed("<LOCAL_ACTION>{\"actionType\":\"weather.get_current\",\"payload\":{}}</LOCAL_ACTION>"))
            append(extractor.finish())
        }

        assertEquals("我查一下。", speech)
        assertTrue(!speech.contains("weather.get_current"))
    }
}
