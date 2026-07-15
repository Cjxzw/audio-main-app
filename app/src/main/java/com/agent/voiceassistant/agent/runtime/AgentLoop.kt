package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import java.util.UUID

class AgentLoop(
    private val runtime: Runtime,
    private val eventSink: (AgentEvent) -> Unit = {},
) {
    data class Config(
        val messages: List<CloudSpeechClient.LlmMessage>,
        val thinkingMode: CloudSpeechClient.ThinkingMode,
        val maxModelCalls: Int,
        val maxCompletionTokens: Int,
        val allowReasoningEscalation: Boolean,
        val beforeSpeech: suspend () -> Unit = {},
    )

    data class ModelTurn(
        val completion: CloudSpeechClient.ChatCompletion,
        val streamedSpeech: Boolean,
    )

    sealed interface Outcome {
        data class Completed(
            val finalText: String,
            val playedSpeech: Boolean,
        ) : Outcome

        data class Escalate(val reason: String) : Outcome
    }

    interface Runtime {
        fun toolDefinitions(allowReasoningEscalation: Boolean): List<CloudSpeechClient.ToolDefinition>

        suspend fun modelTurn(
            request: CloudSpeechClient.ChatRequest,
            beforeSpeech: suspend () -> Unit,
            onStreamEvent: (CloudSpeechClient.ChatStreamEvent) -> Unit,
        ): ModelTurn

        fun normalizeAssistant(message: CloudSpeechClient.LlmMessage): CloudSpeechClient.LlmMessage
        fun isReasoningEscalation(call: CloudSpeechClient.ToolCall): Boolean
        fun reasoningEscalationReason(call: CloudSpeechClient.ToolCall): String
        fun toolDisplayName(toolName: String): String
        suspend fun executeTool(call: CloudSpeechClient.ToolCall): CloudSpeechClient.LlmMessage
        fun blockedTool(call: CloudSpeechClient.ToolCall, reason: String): CloudSpeechClient.LlmMessage
        suspend fun finishAssistant(message: CloudSpeechClient.LlmMessage, streamedSpeech: Boolean): Boolean
    }

    suspend fun run(config: Config, turnId: String = UUID.randomUUID().toString()): Outcome {
        require(config.maxModelCalls > 0) { "maxModelCalls must be positive" }
        val workingMessages = config.messages.toMutableList()
        val completedToolCallIds = mutableSetOf<String>()
        var previousToolBatchSignature: String? = null
        var playedSpeech = false

        eventSink(AgentEvent.AgentStarted(turnId))
        eventSink(AgentEvent.TurnStarted(turnId, config.thinkingMode))
        try {
            repeat(config.maxModelCalls) { step ->
                eventSink(AgentEvent.MessageStarted(turnId, step + 1))
                val request = CloudSpeechClient.ChatRequest(
                    messages = workingMessages,
                    tools = runtime.toolDefinitions(config.allowReasoningEscalation && step == 0),
                    thinkingMode = config.thinkingMode,
                    maxCompletionTokens = config.maxCompletionTokens,
                )
                val streamed = runtime.modelTurn(request, config.beforeSpeech) { streamEvent ->
                    when (streamEvent) {
                        is CloudSpeechClient.ChatStreamEvent.ContentDelta ->
                            eventSink(AgentEvent.ContentDelta(turnId, streamEvent.text))
                        is CloudSpeechClient.ChatStreamEvent.ReasoningDelta ->
                            eventSink(AgentEvent.ReasoningDelta(turnId, streamEvent.text))
                        is CloudSpeechClient.ChatStreamEvent.ToolCallDelta,
                        is CloudSpeechClient.ChatStreamEvent.Finished -> Unit
                    }
                }
                playedSpeech = playedSpeech || streamed.streamedSpeech
                val assistant = runtime.normalizeAssistant(streamed.completion.message)
                eventSink(AgentEvent.MessageFinished(turnId, assistant))

                val escalation = assistant.toolCalls.firstOrNull(runtime::isReasoningEscalation)
                if (escalation != null) {
                    if (!config.allowReasoningEscalation || step != 0) {
                        error("本回合深度思考升级请求无效")
                    }
                    eventSink(AgentEvent.AgentFinished(turnId))
                    return Outcome.Escalate(runtime.reasoningEscalationReason(escalation))
                }

                if (assistant.toolCalls.isEmpty()) {
                    val finalText = assistant.content.orEmpty().trim()
                    if (finalText.isBlank()) error("模型未返回正文或工具调用")
                    playedSpeech = runtime.finishAssistant(assistant, streamed.streamedSpeech) || playedSpeech
                    eventSink(AgentEvent.TurnFinished(turnId, finalText))
                    eventSink(AgentEvent.AgentFinished(turnId))
                    return Outcome.Completed(finalText, playedSpeech)
                }

                workingMessages += assistant
                val batchSignature = assistant.toolCalls.joinToString("|") { call ->
                    "${call.name}:${call.arguments.trim()}"
                }
                val repeatedBatch = batchSignature == previousToolBatchSignature
                previousToolBatchSignature = batchSignature

                for (call in assistant.toolCalls) {
                    eventSink(AgentEvent.ToolStarted(turnId, call, runtime.toolDisplayName(call.name)))
                    val blockedReason = when {
                        !completedToolCallIds.add(call.id) ->
                            "重复的 tool_call_id，调用未再次执行。请基于已有结果继续。"
                        repeatedBatch ->
                            "检测到连续重复工具调用，调用已停止。请改变参数或直接总结已有结果。"
                        else -> null
                    }
                    val toolMessage = if (blockedReason == null) {
                        runtime.executeTool(call)
                    } else {
                        runtime.blockedTool(call, blockedReason)
                    }
                    eventSink(AgentEvent.ToolFinished(turnId, call, toolMessage, blockedReason != null))
                    workingMessages += toolMessage
                }
            }
            error("Agent 工具调用超过本回合上限")
        } catch (error: Throwable) {
            eventSink(
                AgentEvent.AgentFailed(
                    turnId = turnId,
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            throw error
        }
    }
}
