package com.agent.voiceassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongDetailsPolicyTest {
    @Test
    fun `long marked details are replaced with workspace dump`() {
        val details = "甲".repeat(LongDetailsPolicy.MAX_INLINE_CHARACTERS + 1)

        val result = LongDetailsPolicy.normalize(
            "结论\n<DETAILS>$details</DETAILS>",
        ) { "/workspace/长文本转储08051230.md" }

        assertEquals("/workspace/长文本转储08051230.md", result.dumpedPath)
        assertTrue(result.content.startsWith("结论\n\n<DETAILS>"))
        assertTrue(result.content.contains("完整内容已转储至 `/workspace/长文本转储08051230.md`"))
        assertEquals(result.dumpedPath, LongDetailsPolicy.dumpedPath(ReplyDetailPolicy.extract(result.content).detailsText))
    }

    @Test
    fun `short details remain inline`() {
        val markdown = "结论\n<DETAILS>短详情</DETAILS>"

        val result = LongDetailsPolicy.normalize(markdown) { error("should not dump") }

        assertEquals(markdown, result.content)
        assertNull(result.dumpedPath)
        assertNull(LongDetailsPolicy.dumpedPath("普通详情"))
    }
}
