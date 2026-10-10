package com.agent.voiceassistant.data

import org.junit.Assert.*
import org.junit.Test

class RealtimeContextProjectionTest {
    private fun message(role: String, content: String, id: String = content, timestamp: Long = 0) =
        StoredMessage(id, role, content, timestamp = timestamp)

    @Test fun snapshotKeepsWholeTurnsAndUsesLatestTen() {
        val messages = (1..12).flatMap { listOf(message("user", "问$it"), message("assistant", "答$it")) }
        val snapshot = RealtimeContextProjection.snapshot(messages)
        assertFalse(snapshot.contains("用户：问2\n"))
        assertTrue(snapshot.startsWith("用户：问3\n"))
        assertTrue(snapshot.endsWith("助手：答12"))
        assertEquals("用户：问12\n助手：答12", RealtimeContextProjection.snapshot(messages, maxChars = 13))
    }

    @Test fun oversizedLatestTurnProducesEmptySnapshotRatherThanHalfTurn() {
        val messages = listOf(message("user", "旧问题"), message("assistant", "旧回复"),
            message("user", "新问题"), message("assistant", "字".repeat(1300)))
        assertEquals("", RealtimeContextProjection.snapshot(messages))
    }

    @Test fun snapshotIncludesBodyAndToolNamesButExcludesResultsAndReasoning() {
        val call = message("assistant", "我先查一下").copy(toolCalls = listOf(StoredToolCall("call", "search", "secret")), reasoningText = "隐藏思考")
        val result = message("tool", "长工具结果".repeat(1000)).copy(toolCallId = "call", toolStatus = "SUCCEEDED")
        val messages = listOf(message("user", "查一下"), call, result, message("assistant", "答案"))
        val snapshot = RealtimeContextProjection.snapshot(messages)
        assertTrue(snapshot.contains("我先查一下"))
        assertTrue(snapshot.contains("[工具调用] search"))
        assertFalse(snapshot.contains("长工具结果"))
        assertFalse(snapshot.contains("隐藏思考"))
        assertFalse(snapshot.contains("secret"))
    }

    @Test fun transcriptDoesNotLimitMessageCountOrBodyLength() {
        val longText = "正文".repeat(1000)
        val messages = (1..60).flatMap { listOf(message("user", "问题$it"), message("assistant", longText)) }
        val transcript = RealtimeContextProjection.transcript(messages)
        assertTrue(transcript.contains("用户：问题1\n"))
        assertTrue(transcript.contains("用户：问题60\n"))
        assertEquals(60, transcript.windowed(longText.length).count { it == longText })
    }

    @Test fun transcriptKeepsOnlyUserAndFinalAssistant正文() {
        val messages = listOf(
            message("user", "查一下"),
            message("assistant", "我先查一下").copy(reasoningText = "隐藏思考", toolCalls = listOf(StoredToolCall("c", "search", "秘密参数"))),
            message("tool", "秘密工具结果").copy(toolCallId = "c", toolStatus = "SUCCEEDED"),
            message("assistant", "最终答案"),
        )
        val transcript = RealtimeContextProjection.transcript(messages)
        assertTrue(transcript.contains("用户：查一下"))
        assertTrue(transcript.contains("助手：我先查一下"))
        assertTrue(transcript.contains("助手：最终答案"))
        assertFalse(transcript.contains("秘密工具结果"))
        assertFalse(transcript.contains("[工具调用]"))
    }

    @Test fun transcriptCollapsesEchoedUserLines() {
        val transcript = RealtimeContextProjection.transcript(
            listOf(message("user", "你好"), message("assistant", "你好"), message("user", "你好")),
        )
        assertEquals(2, transcript.lines().size)
    }

    @Test fun taggedThinkingIsExcludedFromContextAndReports() {
        val raw = "<thinking>内部思考</thinking><answer>最终正文</answer><DETAILS>用户可见详情</DETAILS>"
        val projected = RealtimeContextProjection.transcript(listOf(message("assistant", raw)))
        assertFalse(projected.contains("内部思考"))
        assertTrue(projected.contains("最终正文"))
        assertTrue(projected.contains("用户可见详情"))
    }

    @Test fun unfinishedTurnIsNotPartiallyInjectedAtStartup() {
        val messages = listOf(message("user", "问题"), message("assistant", "未完成").copy(streamState = "STREAMING"))
        assertEquals("", RealtimeContextProjection.snapshot(messages))
        assertTrue(RealtimeContextProjection.transcript(messages).contains("未完成"))
    }

    @Test fun realtimeNotesCountAsIndependentTurnsAndAreOrderedByEndTime() {
        val messages = listOf(
            message("user", "问题一", timestamp = 1_000),
            message("assistant", "回答一", timestamp = 1_001),
            message("user", "问题二", timestamp = 3_000),
            message("assistant", "回答二", timestamp = 3_001),
        )
        val notes = listOf(
            "Realtime 通话记录（1970-01-01 00:00）：通话一",
            "Realtime 通话记录（1970-01-01 00:00）：通话二",
        )
        val snapshot = RealtimeContextProjection.snapshotWithRealtimeNotes(messages, notes, maxTurns = 4, maxChars = 500)
        assertTrue(snapshot.contains("通话一"))
        assertTrue(snapshot.contains("通话二"))
        assertEquals(4, listOf("问题一", "通话一", "问题二", "通话二").count { snapshot.contains(it) })
    }
}
