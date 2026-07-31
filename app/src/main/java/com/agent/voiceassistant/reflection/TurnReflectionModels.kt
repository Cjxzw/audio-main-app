package com.agent.voiceassistant.reflection

import kotlinx.serialization.Serializable

@Serializable
data class ToolCallMetric(
    val callId: String,
    val name: String,
    val round: Int,
    val parallel: Boolean,
    val startedOffsetMs: Long,
    val durationMs: Long,
    val success: Boolean,
    val blocked: Boolean,
)

@Serializable
data class TurnMetrics(
    val turnId: String,
    val startedAt: Long,
    val totalDurationMs: Long,
    val timeToFirstContentMs: Long? = null,
    val timeToFinalAnswerContentMs: Long? = null,
    val timeToFirstAudioMs: Long? = null,
    val streamingDurationMs: Long? = null,
    val initialModelDurationMs: Long = 0,
    val postToolModelDurationMs: Long = 0,
    val toolWallDurationMs: Long = 0,
    val toolAccumulatedDurationMs: Long = 0,
    val maxSingleToolDurationMs: Long = 0,
    val toolCallCount: Int = 0,
    val toolRoundCount: Int = 0,
    val parallelBatchCount: Int = 0,
    val successfulToolCallCount: Int = 0,
    val failedToolCallCount: Int = 0,
    val blockedToolCallCount: Int = 0,
    val usedHubDispatch: Boolean = false,
    val tools: List<ToolCallMetric> = emptyList(),
) {
    fun reflectionTriggers(): List<String> = buildList {
        if (toolCallCount >= 5) add("工具调用数 >= 5")
        if (toolRoundCount >= 3) add("工具轮次 >= 3")
        if (totalDurationMs >= 20_000) add("总耗时 >= 20 秒")
        if (toolWallDurationMs >= 10_000) add("工具耗时 >= 10 秒")
    }
}

@Serializable
data class ReflectionAnalysis(
    val title: String,
    val taskSummary: String,
    val taskNature: List<String>,
    val complexity: String,
    val delegationAssessment: String,
    val preferredCapabilities: List<String> = emptyList(),
    val whyDelegateOrNot: String,
    val whyNotDelegated: String,
    val lesson: String,
    val confidence: Double,
)

@Serializable
data class TurnReflectionRecord(
    val turnId: String,
    val conversationId: String,
    val userRequest: String,
    val source: String,
    val metrics: TurnMetrics,
    val triggerReasons: List<String>,
    val status: String,
    val analysis: ReflectionAnalysis? = null,
    val rawReflection: String = "",
    val reflectionModel: String = "",
    val reflectionPromptVersion: Int = 1,
    val contextHash: String = "",
    val attemptCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)
