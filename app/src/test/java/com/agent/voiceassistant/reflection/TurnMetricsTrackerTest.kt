package com.agent.voiceassistant.reflection

import com.agent.voiceassistant.agent.runtime.AgentEvent
import com.agent.voiceassistant.cloud.CloudSpeechClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnMetricsTrackerTest {
    @Test
    fun `parallel tools count individually but wall time uses interval union`() {
        var elapsed = 100L
        val tracker = TurnMetricsTracker(elapsedMs = { elapsed }, wallClockMs = { 1_000L })
        val first = call("call-1", "web_search")
        val second = call("call-2", "web_search")

        tracker.onEvent(AgentEvent.TurnStarted("turn-1", CloudSpeechClient.ThinkingMode.DISABLED))
        tracker.onEvent(AgentEvent.MessageStarted("turn-1", 1))
        elapsed = 1_000
        tracker.onEvent(AgentEvent.ContentDelta("turn-1", "我先搜索"))
        elapsed = 2_000
        tracker.markModelStreamDone()
        tracker.onEvent(
            AgentEvent.MessageFinished(
                "turn-1",
                CloudSpeechClient.LlmMessage("assistant", toolCalls = listOf(first, second)),
            ),
        )
        tracker.onEvent(AgentEvent.ToolStarted("turn-1", first, "搜索"))
        tracker.onEvent(AgentEvent.ToolStarted("turn-1", second, "搜索"))
        tracker.onEvent(AgentEvent.ParallelToolsStarted("turn-1", listOf(first, second)))
        elapsed = 5_000
        tracker.onEvent(AgentEvent.ToolFinished("turn-1", first, toolResult(first), success = true))
        elapsed = 7_000
        tracker.onEvent(AgentEvent.ToolFinished("turn-1", second, toolResult(second), success = true))
        tracker.onEvent(AgentEvent.MessageStarted("turn-1", 2))
        elapsed = 9_000
        tracker.onEvent(AgentEvent.ContentDelta("turn-1", "完成"))
        tracker.markAudioStarted()
        elapsed = 10_000
        tracker.markModelStreamDone()
        tracker.onEvent(
            AgentEvent.MessageFinished(
                "turn-1",
                CloudSpeechClient.LlmMessage("assistant", content = "完成"),
            ),
        )

        val metrics = tracker.snapshot()

        assertEquals(9_900L, metrics.totalDurationMs)
        assertEquals(900L, metrics.timeToFirstContentMs)
        assertEquals(8_900L, metrics.timeToFinalAnswerContentMs)
        assertEquals(8_900L, metrics.timeToFirstAudioMs)
        assertEquals(5_000L, metrics.toolWallDurationMs)
        assertEquals(8_000L, metrics.toolAccumulatedDurationMs)
        assertEquals(5_000L, metrics.maxSingleToolDurationMs)
        assertEquals(2, metrics.toolCallCount)
        assertEquals(1, metrics.toolRoundCount)
        assertEquals(1, metrics.parallelBatchCount)
        assertTrue(metrics.tools.all(ToolCallMetric::parallel))
    }

    @Test
    fun `reflection triggers focus on routing anomalies`() {
        assertEquals(listOf("未委派且工具调用数 >= 5"), metrics(toolCallCount = 5).reflectionTriggers())
        assertEquals(listOf("未委派且工具轮次 >= 3"), metrics(toolRoundCount = 3).reflectionTriggers())
        assertEquals(listOf("重型任务未委派"), metrics().reflectionTriggers(heavyTaskIntent = true))
        assertEquals(
            listOf("有效任务预算已触发"),
            metrics(activeBudgetBlocked = true).reflectionTriggers(),
        )
        assertTrue(metrics().reflectionTriggers().isEmpty())
    }

    @Test
    fun `blocked tool still counts and terminal tool extends total duration`() {
        var elapsed = 0L
        val tracker = TurnMetricsTracker(elapsedMs = { elapsed }, wallClockMs = { 1_000L })
        val blocked = call("blocked-1", "dangerous_tool")

        tracker.onEvent(AgentEvent.TurnStarted("turn-1", CloudSpeechClient.ThinkingMode.DISABLED))
        tracker.onEvent(AgentEvent.MessageStarted("turn-1", 1))
        elapsed = 1_000
        tracker.markModelStreamDone()
        tracker.onEvent(
            AgentEvent.MessageFinished(
                "turn-1",
                CloudSpeechClient.LlmMessage("assistant", toolCalls = listOf(blocked)),
            ),
        )
        tracker.onEvent(AgentEvent.ToolStarted("turn-1", blocked, "等待授权"))
        elapsed = 12_000
        tracker.onEvent(
            AgentEvent.ToolFinished(
                "turn-1",
                blocked,
                toolResult(blocked),
                success = false,
                blocked = true,
            ),
        )

        val metrics = tracker.snapshot()

        assertEquals(12_000L, metrics.totalDurationMs)
        assertEquals(11_000L, metrics.toolWallDurationMs)
        assertEquals(1, metrics.toolCallCount)
        assertEquals(1, metrics.toolRoundCount)
        assertEquals(1, metrics.blockedToolCallCount)
        assertEquals(0, metrics.failedToolCallCount)
        assertEquals(listOf("存在被策略阻止的工具调用"), metrics.reflectionTriggers())
    }

    @Test
    fun `reflection validator accepts strict schema and rejects prose`() {
        val valid = """{"title":"[反思] 多来源调研","taskSummary":"比较车型","taskNature":["research"],"complexity":"medium","delegationAssessment":"prefer_delegate","preferredCapabilities":["research"],"whyDelegateOrNot":"需要多来源比较","whyNotDelegated":"本地搜索可用","lesson":"多来源比较可作为委派候选","confidence":0.91}"""

        assertTrue(ReflectionValidator.parse(valid).isSuccess)
        assertFalse(ReflectionValidator.parse("[反思] 应该委派").isSuccess)
        assertFalse(ReflectionValidator.parse(valid.replace("[反思]", "总结")).isSuccess)
    }

    private fun call(id: String, name: String) = CloudSpeechClient.ToolCall(id, name, "{}")

    private fun toolResult(call: CloudSpeechClient.ToolCall) =
        CloudSpeechClient.LlmMessage("tool", "ok", toolCallId = call.id)

    private fun metrics(
        toolCallCount: Int = 0,
        toolRoundCount: Int = 0,
        totalDurationMs: Long = 0,
        toolWallDurationMs: Long = 0,
        activeBudgetBlocked: Boolean = false,
    ) = TurnMetrics(
        turnId = "turn",
        startedAt = 0,
        totalDurationMs = totalDurationMs,
        toolCallCount = toolCallCount,
        toolRoundCount = toolRoundCount,
        toolWallDurationMs = toolWallDurationMs,
        activeBudgetBlocked = activeBudgetBlocked,
    )
}
