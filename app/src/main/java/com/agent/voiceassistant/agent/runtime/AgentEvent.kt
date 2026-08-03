package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.cloud.CloudSpeechClient

sealed interface AgentEvent {
    val turnId: String
    val timestamp: Long

    data class AgentStarted(
        override val turnId: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class TurnStarted(
        override val turnId: String,
        val thinkingMode: CloudSpeechClient.ThinkingMode,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class ThinkingModeChanged(
        override val turnId: String,
        val thinkingMode: CloudSpeechClient.ThinkingMode,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class AutomaticThinkingEscalated(
        override val turnId: String,
        val toolCallCount: Int,
        val triggerCalls: List<CloudSpeechClient.ToolCall>,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class MessageStarted(
        override val turnId: String,
        val modelCall: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class FinalResponseRetry(
        override val turnId: String,
        val attempt: Int,
        val maxRetries: Int,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class ContentDelta(
        override val turnId: String,
        val text: String,
        val userVisible: Boolean = true,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class ToolCallDetected(
        override val turnId: String,
        val toolName: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class ToolCallRejected(
        override val turnId: String,
        val toolCallId: String,
        val toolName: String,
        val reason: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class ReasoningDelta(
        override val turnId: String,
        val text: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class ToolStarted(
        override val turnId: String,
        val call: CloudSpeechClient.ToolCall,
        val displayName: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class ToolProgress(
        override val turnId: String,
        val toolCallId: String,
        val detail: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class ParallelToolsStarted(
        override val turnId: String,
        val calls: List<CloudSpeechClient.ToolCall>,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class ToolFinished(
        override val turnId: String,
        val call: CloudSpeechClient.ToolCall,
        val result: CloudSpeechClient.LlmMessage,
        val success: Boolean,
        val blocked: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class MessageFinished(
        override val turnId: String,
        val message: CloudSpeechClient.LlmMessage,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class TurnFinished(
        override val turnId: String,
        val finalText: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class AgentFinished(
        override val turnId: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class AgentFailed(
        override val turnId: String,
        val error: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent
}
