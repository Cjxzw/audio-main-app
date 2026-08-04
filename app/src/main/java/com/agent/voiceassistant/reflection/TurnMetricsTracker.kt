package com.agent.voiceassistant.reflection

import com.agent.voiceassistant.agent.runtime.AgentEvent
import com.agent.voiceassistant.tools.MainToolRegistry

class TurnMetricsTracker(
    private val elapsedMs: () -> Long,
    private val wallClockMs: () -> Long,
) {
    constructor() : this(
        elapsedMs = { android.os.SystemClock.elapsedRealtime() },
        wallClockMs = System::currentTimeMillis,
    )

    private data class StartedTool(
        val name: String,
        val startedAt: Long,
        val round: Int,
        val parallel: Boolean,
    )

    private data class Interval(val start: Long, val end: Long)

    private var turnId = ""
    private var startedElapsed = 0L
    private var startedWall = 0L
    private var lastModelStreamDoneElapsed: Long? = null
    private var firstContentElapsed: Long? = null
    private var finalAnswerContentElapsed: Long? = null
    private var currentModelFirstContentElapsed: Long? = null
    private var firstAudioElapsed: Long? = null
    private var firstModelDurationMs = 0L
    private var modelStreamCount = 0
    private var currentModelStartedElapsed: Long? = null
    private var postToolModelDurationMs = 0L
    private var currentRound = 0
    private var lastToolFinishedElapsed: Long? = null
    private var parallelBatchCount = 0
    private var currentParallelCallIds = emptySet<String>()
    private var automaticReasoningEscalated = false
    private var activeBudgetBlocked = false
    private var activeTaskDurationMs = 0L
    private val startedTools = mutableMapOf<String, StartedTool>()
    private val toolMetrics = mutableListOf<ToolCallMetric>()
    private val toolIntervals = mutableListOf<Interval>()

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TurnStarted -> {
                turnId = event.turnId
                startedElapsed = elapsedMs()
                startedWall = wallClockMs()
            }
            is AgentEvent.MessageStarted -> {
                val now = elapsedMs()
                currentModelStartedElapsed = now
                currentModelFirstContentElapsed = null
            }
            is AgentEvent.MessageFinished -> {
                val businessCalls = event.message.toolCalls.filterNot {
                    it.name == MainToolRegistry.TOOL_REQUEST_DEEP_REASONING
                }
                if (businessCalls.isNotEmpty()) currentRound += 1
                if (event.message.toolCalls.isEmpty() && finalAnswerContentElapsed == null) {
                    finalAnswerContentElapsed = currentModelFirstContentElapsed
                }
            }
            is AgentEvent.ContentDelta -> if (currentModelFirstContentElapsed == null) {
                val now = elapsedMs()
                currentModelFirstContentElapsed = now
                if (firstContentElapsed == null) firstContentElapsed = now
            }
            is AgentEvent.ParallelToolsStarted -> {
                parallelBatchCount += 1
                currentParallelCallIds = event.calls.mapTo(hashSetOf()) { it.id }
                event.calls.forEach { call ->
                    startedTools[call.id]?.let { started ->
                        startedTools[call.id] = started.copy(parallel = true)
                    }
                }
            }
            is AgentEvent.AutomaticThinkingEscalated -> automaticReasoningEscalated = true
            is AgentEvent.ActiveToolBudgetExceeded -> {
                activeBudgetBlocked = true
                activeTaskDurationMs = maxOf(activeTaskDurationMs, event.activeElapsedMs)
            }
            is AgentEvent.ToolStarted -> if (event.call.name != MainToolRegistry.TOOL_REQUEST_DEEP_REASONING) {
                startedTools[event.call.id] = StartedTool(
                    name = event.call.name,
                    startedAt = elapsedMs(),
                    round = currentRound.coerceAtLeast(1),
                    parallel = event.call.id in currentParallelCallIds,
                )
            }
            is AgentEvent.ToolFinished -> if (event.call.name != MainToolRegistry.TOOL_REQUEST_DEEP_REASONING) {
                val finishedAt = elapsedMs()
                lastToolFinishedElapsed = finishedAt
                val started = startedTools.remove(event.call.id) ?: return
                val duration = (finishedAt - started.startedAt).coerceAtLeast(0)
                toolMetrics += ToolCallMetric(
                    callId = event.call.id,
                    name = started.name,
                    round = started.round,
                    parallel = started.parallel,
                    startedOffsetMs = (started.startedAt - startedElapsed).coerceAtLeast(0),
                    durationMs = duration,
                    success = event.success && !event.blocked,
                    blocked = event.blocked,
                )
                toolIntervals += Interval(started.startedAt, finishedAt)
                currentParallelCallIds = currentParallelCallIds - event.call.id
            }
            else -> Unit
        }
    }

    fun markModelStreamDone() {
        val now = elapsedMs()
        lastModelStreamDoneElapsed = now
        val duration = currentModelStartedElapsed?.let { (now - it).coerceAtLeast(0) } ?: 0
        modelStreamCount += 1
        if (modelStreamCount == 1) firstModelDurationMs = duration else if (currentRound > 0) {
            postToolModelDurationMs += duration
        }
        currentModelStartedElapsed = null
    }

    fun markAudioStarted() {
        if (firstAudioElapsed == null) firstAudioElapsed = elapsedMs()
    }

    fun snapshot(): TurnMetrics {
        check(turnId.isNotBlank()) { "turn has not started" }
        val finishedAt = listOfNotNull(lastModelStreamDoneElapsed, lastToolFinishedElapsed)
            .maxOrNull() ?: elapsedMs()
        val firstContent = firstContentElapsed
        val finalContent = finalAnswerContentElapsed
        val toolWall = mergedDuration(toolIntervals)
        return TurnMetrics(
            turnId = turnId,
            startedAt = startedWall,
            totalDurationMs = (finishedAt - startedElapsed).coerceAtLeast(0),
            timeToFirstContentMs = firstContent?.let { (it - startedElapsed).coerceAtLeast(0) },
            timeToFinalAnswerContentMs = finalContent?.let { (it - startedElapsed).coerceAtLeast(0) },
            timeToFirstAudioMs = firstAudioElapsed?.let { (it - startedElapsed).coerceAtLeast(0) },
            streamingDurationMs = finalContent?.let { (finishedAt - it).coerceAtLeast(0) },
            initialModelDurationMs = firstModelDurationMs,
            postToolModelDurationMs = postToolModelDurationMs,
            toolWallDurationMs = toolWall,
            toolAccumulatedDurationMs = toolMetrics.sumOf(ToolCallMetric::durationMs),
            maxSingleToolDurationMs = toolMetrics.maxOfOrNull(ToolCallMetric::durationMs) ?: 0,
            toolCallCount = toolMetrics.size,
            toolRoundCount = toolMetrics.map(ToolCallMetric::round).distinct().size,
            parallelBatchCount = parallelBatchCount,
            successfulToolCallCount = toolMetrics.count(ToolCallMetric::success),
            failedToolCallCount = toolMetrics.count { !it.success && !it.blocked },
            blockedToolCallCount = toolMetrics.count(ToolCallMetric::blocked),
            usedHubDispatch = toolMetrics.any { it.name == MainToolRegistry.TOOL_HUB_DISPATCH_TASK },
            automaticReasoningEscalated = automaticReasoningEscalated,
            activeBudgetBlocked = activeBudgetBlocked,
            activeTaskDurationMs = activeTaskDurationMs,
            tools = toolMetrics.toList(),
        )
    }

    private fun mergedDuration(intervals: List<Interval>): Long {
        if (intervals.isEmpty()) return 0
        val sorted = intervals.sortedBy(Interval::start)
        var start = sorted.first().start
        var end = sorted.first().end
        var total = 0L
        for (interval in sorted.drop(1)) {
            if (interval.start <= end) {
                end = maxOf(end, interval.end)
            } else {
                total += (end - start).coerceAtLeast(0)
                start = interval.start
                end = interval.end
            }
        }
        return total + (end - start).coerceAtLeast(0)
    }
}
