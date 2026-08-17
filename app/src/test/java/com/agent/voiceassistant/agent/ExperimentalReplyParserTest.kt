package com.agent.voiceassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalReplyParserTest {
    @Test
    fun `extracts all supported sections without tags`() {
        val parsed = ExperimentalReplyParser.parse(
            "<thinking>先判断</thinking><answer>正文</answer><details>## 依据</details>",
        )

        assertEquals("先判断", parsed.thinking)
        assertEquals("正文", parsed.answer)
        assertEquals("## 依据", parsed.details)
        assertTrue(parsed.hasUsableAnswer)
    }

    @Test
    fun `new section recovers an unclosed thinking tag`() {
        val parsed = ExperimentalReplyParser.parse("<thinking>先分析<answer>最终结论</answer>")

        assertEquals("先分析", parsed.thinking)
        assertEquals("最终结论", parsed.answer)
    }

    @Test
    fun `misplaced close tag becomes a boundary and trailing text is answer`() {
        val parsed = ExperimentalReplyParser.parse("<thinking>先分析</answer>最终结论")

        assertEquals("先分析", parsed.thinking)
        assertEquals("最终结论", parsed.answer)
    }

    @Test
    fun `untagged natural language is accepted as answer`() {
        val parsed = ExperimentalReplyParser.parse("直接回答用户。")

        assertEquals("直接回答用户。", parsed.answer)
        assertFalse(parsed.recognizedTags)
    }

    @Test
    fun `thinking only output has no usable answer`() {
        val parsed = ExperimentalReplyParser.parse("<working>还在处理")

        assertEquals("还在处理", parsed.thinking)
        assertFalse(parsed.hasUsableAnswer)
    }

    @Test
    fun `contract tags inside fenced markdown stay literal`() {
        val parsed = ExperimentalReplyParser.parse("<answer>正文</answer><details>```xml\n<thinking>x</thinking>\n```</details>")

        assertEquals("正文", parsed.answer)
        assertTrue(parsed.details.contains("<thinking>x</thinking>"))
    }

    @Test
    fun `streaming parser withholds split boundary tags`() {
        assertEquals("", ExperimentalReplyParser.parseStreaming("<think").answer)
        val parsed = ExperimentalReplyParser.parseStreaming("<thinking>分析</think")
        assertEquals("分析", parsed.thinking)
        assertEquals("", parsed.answer)
    }
}
