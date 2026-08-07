package com.agent.voiceassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongDetailsPolicyTest {
    @Test
    fun `short details remain inline`() {
        val input = "结论。\n<DETAILS>${"详".repeat(1_000)}</DETAILS>"
        var dumped = false

        val result = LongDetailsPolicy.normalize(input) {
            dumped = true
            "/workspace/unused.md"
        }

        assertEquals(input, result.content)
        assertNull(result.dumpedPath)
        assertFalse(dumped)
    }

    @Test
    fun `combined long details are replaced with dump reference`() {
        val input = "结论。\n<DETAILS>${"甲".repeat(600)}</DETAILS>\n<DETAILS>${"乙".repeat(600)}</DETAILS>"
        var dumpedText = ""

        val result = LongDetailsPolicy.normalize(input) {
            dumpedText = it
            "/workspace/长文本转储08051647.md"
        }

        assertEquals(1_202, dumpedText.length)
        assertTrue(result.content.startsWith("结论。"))
        assertFalse(result.content.contains("甲"))
        assertFalse(result.content.contains("乙"))
        assertEquals(
            "/workspace/长文本转储08051647.md",
            LongDetailsPolicy.dumpedPath(ReplyDetailPolicy.extract(result.content).detailsText),
        )
    }
}
