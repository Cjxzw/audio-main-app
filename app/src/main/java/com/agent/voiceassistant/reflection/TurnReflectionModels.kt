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
    val automaticReasoningEscalated: Boolean = false,
    val activeBudgetBlocked: Boolean = false,
    val activeTaskDurationMs: Long = 0,
    val tools: List<ToolCallMetric> = emptyList(),
) {
    fun reflectionTriggers(heavyTaskIntent: Boolean = false): List<String> = buildList {
        if (activeBudgetBlocked) add("有效任务预算已触发")
        if (heavyTaskIntent && !usedHubDispatch) add("重型任务未委派")
        if (automaticReasoningEscalated && !usedHubDispatch && toolRoundCount >= 2) {
            add("复杂任务升级后仍扩展本地工具")
        }
        if (toolCallCount >= 5 && !usedHubDispatch) add("未委派且工具调用数 >= 5")
        if (toolRoundCount >= 3 && !usedHubDispatch) add("未委派且工具轮次 >= 3")
        if (failedToolCallCount >= 3) add("工具连续或累计失败较多")
        if (blockedToolCallCount > 0) add("存在被策略阻止的工具调用")
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
