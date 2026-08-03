package com.agent.voiceassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyDetailPolicyTest {
    @Test
    fun `extracts body and details independently`() {
        val extraction = ReplyDetailPolicy.extract(
            "结论已经确认。\n<DETAILS>\n## 详情\n- 一项\n</DETAILS>",
        )

        assertEquals("结论已经确认。", extraction.speakableText)
        assertTrue(extraction.hasMarkedDetails)
        assertEquals("## 详情\n- 一项", extraction.detailsText)
    }

    @Test
    fun `extracts incomplete details without leaking the marker into body`() {
        val extraction = ReplyDetailPolicy.extract("结论。<DETAILS>尚未闭合的详情")

        assertEquals("结论。", extraction.speakableText)
        assertEquals("尚未闭合的详情", extraction.detailsText)
        assertTrue(extraction.hasMarkedDetails)
    }
}
