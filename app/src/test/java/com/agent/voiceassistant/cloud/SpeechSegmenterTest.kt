package com.agent.voiceassistant.cloud

import com.agent.voiceassistant.agent.SpokenReplyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    @Test
    fun `default segmenter keeps short neighboring sentences together`() {
        val segmenter = SpeechSegmenter()
        val output = segmenter.feed("这是第一句，用来测试连续语气。这里是第二句，希望和前一句保持连贯。今天我们继续讨论语音助手的体验。除此之外没有其他事情。希望这几句话不要被过早拆开。").toMutableList()
        segmenter.flush()?.let { output += it }

        assertEquals(1, output.size)
    }

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

    @Test
    fun `markdown details are skipped while surrounding text remains speakable`() {
        val extractor = StreamingSpeechExtractor()
        val speech = buildString {
            append(extractor.feed("结论如下。\n``"))
            append(extractor.feed("`json\n{\"status\":\"ok\"}\n`"))
            append(extractor.feed("``\n请查看详情。"))
            append(extractor.finish())
        }

        assertTrue(speech.contains("结论如下"))
        assertTrue(speech.contains("请查看详情"))
        assertTrue(speech.endsWith("该回复中有详细信息，请查看手机。"))
        assertTrue(!speech.contains("status"))
    }

    @Test
    fun `details only markdown emits a phone notice`() {
        val extractor = StreamingSpeechExtractor()
        val speech = buildString {
            append(extractor.feed("```xml\n<result>detail</result>\n```"))
            append(extractor.finish())
        }

        assertEquals("该回复中有详细信息，请查看手机。", speech)
    }

    @Test
    fun `leading json is display content without a detail notice`() {
        val extractor = StreamingSpeechExtractor()
        val speech = buildString {
            append(extractor.feed("{\"status\":\"ok\"}"))
            append(extractor.finish())
        }

        assertEquals("", speech)
    }

    @Test
    fun `details marker stops nested markdown from entering speech`() {
        val extractor = StreamingSpeechExtractor()
        val speech = buildString {
            append(extractor.feed("问题已经定位，修复点在会话恢复流程。\n<DETA"))
            append(extractor.feed("ILS>\n## 详细信息\n```json\n{\"status\":\"ok\"}\n```\n</DETAILS>"))
            append(extractor.finish())
        }

        assertTrue(speech.startsWith("问题已经定位"))
        assertTrue(!speech.contains("##"))
        assertTrue(!speech.contains("后续内容"))
        assertTrue(speech.endsWith("该回复中有详细信息，请查看手机。"))
    }

    @Test
    fun `streaming speech is not limited to three sentences`() {
        val extractor = StreamingSpeechExtractor()
        val speech = buildString {
            append(extractor.feed("第一句。第二句。第三句。第四句。第五句。"))
            append(extractor.finish())
        }

        assertTrue(speech.contains("第一句。第二句。第三句。第四句。第五句。"))
        assertTrue(!speech.contains("该回复中有详细信息，请查看手机。"))
    }

    @Test
    fun `markdown table is skipped without a detail notice`() {
        val extractor = StreamingSpeechExtractor()
        val speech = buildString {
            append(extractor.feed("结论正常。\n| 字段 | 值 |\n| --- | --- |\n| status | ok |\n\n后续正文继续播报。"))
            append(extractor.finish())
        }

        assertTrue(speech.contains("结论正常"))
        assertTrue(speech.contains("后续正文继续播报"))
        assertTrue(!speech.contains("status"))
        assertTrue(!speech.contains(SpokenReplyPolicy.DETAILS_NOTICE))
    }

    @Test
    fun `loose function xml without tool wrapper is not exposed as speech`() {
        val extractor = StreamingSpeechExtractor()
        val speech = buildString {
            append(extractor.feed("<function=exec><parameter=command>pwd</parameter></function>"))
            append(extractor.finish())
        }

        assertEquals("", speech)
    }

    @Test
    fun `inline markdown emphasis markers are not spoken`() {
        val extractor = StreamingSpeechExtractor()
        val speech = buildString {
            append(extractor.feed("**结论**是保持_main_在线。"))
            append(extractor.finish())
        }

        assertEquals("结论是保持main在线。", speech)
    }
}
