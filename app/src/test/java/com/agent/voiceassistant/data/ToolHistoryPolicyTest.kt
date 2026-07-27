package com.agent.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolHistoryPolicyTest {
    @Test
    fun `keeps short tool results unchanged`() {
        assertEquals("short result", ToolHistoryPolicy.compact("short result", "turn", "call"))
        assertEquals("short result", ToolHistoryPolicy.prepareForCurrentTurn("short result"))
    }

    @Test
    fun `warns in current turn when result will be truncated in later turns`() {
        val content = "x".repeat(5_000)

        val prepared = ToolHistoryPolicy.prepareForCurrentTurn(content)

        assertTrue(prepared.contains("已超过 3000 字"))
        assertTrue(prepared.contains("只会保留本条结果的开头 3000 字"))
        assertTrue(prepared.endsWith(content))
    }

    @Test
    fun `truncates current turn results at twelve thousand characters with an explicit marker`() {
        val content = "HEAD" + "x".repeat(13_000) + "TAIL"

        val prepared = ToolHistoryPolicy.prepareForCurrentTurn(content)

        assertTrue(prepared.contains("已超过 12000 字"))
        assertTrue(prepared.contains("采用保留开头的方式"))
        assertTrue(prepared.contains(content.take(ToolHistoryPolicy.MAX_CURRENT_TURN_RESULT_CHARS)))
        assertTrue(!prepared.contains("TAIL"))
    }

    @Test
    fun `compacts long results to exactly the history limit while preserving only the start`() {
        val content = "HEAD" + "x".repeat(5_000) + "TAIL"

        val compact = ToolHistoryPolicy.compact(content, "turn-1", "call-1")

        assertEquals(ToolHistoryPolicy.MAX_PERSISTED_RESULT_CHARS, compact.length)
        assertTrue(compact.startsWith("HEAD"))
        assertTrue(!compact.contains("TAIL"))
        assertTrue(compact.contains("后续回合历史在此处截断"))
        assertTrue(compact.contains("保留开头"))
    }

    @Test
    fun `quarantines malformed assistant tool call and linked result without deleting chat`() {
        val malformedAssistant = StoredMessage(
            id = "assistant-bad",
            role = "assistant",
            content = "partial reply",
            timestamp = 1,
            toolCalls = listOf(
                StoredToolCall("call-bad", "...", "{\"broken\":\"value"),
            ),
            llmVisible = true,
            chatVisible = true,
        )
        val linkedTool = StoredMessage(
            id = "tool-bad",
            role = "tool",
            content = "tool does not exist",
            timestamp = 2,
            toolCallId = "call-bad",
            llmVisible = true,
            chatVisible = true,
        )
        val normalUser = StoredMessage(
            id = "user-ok",
            role = "user",
            content = "continue",
            timestamp = 3,
        )
        val session = ConversationSession(
            id = "session",
            title = "session",
            createdAt = 1,
            updatedAt = 3,
            messages = mutableListOf(malformedAssistant, linkedTool, normalUser),
        )

        val quarantined = quarantineMalformedToolHistory(session)

        assertEquals(2, quarantined)
        assertEquals(3, session.messages.size)
        assertEquals(false, session.messages[0].llmVisible)
        assertEquals(false, session.messages[1].llmVisible)
        assertEquals(true, session.messages[0].chatVisible)
        assertEquals("continue", session.messages[2].content)
    }
}
