package com.agent.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolHistoryPolicyTest {
    @Test
    fun `keeps short tool results unchanged`() {
        assertEquals("short result", ToolHistoryPolicy.compact("short result", "turn", "call"))
    }

    @Test
    fun `compacts long results to exactly the history limit while preserving both ends`() {
        val content = "HEAD" + "x".repeat(5_000) + "TAIL"

        val compact = ToolHistoryPolicy.compact(content, "turn-1", "call-1")

        assertEquals(ToolHistoryPolicy.MAX_PERSISTED_RESULT_CHARS, compact.length)
        assertTrue(compact.startsWith("HEAD"))
        assertTrue(compact.endsWith("TAIL"))
        assertTrue(compact.contains("长期历史已压缩"))
    }
}
