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
        val maxToolRounds: Int,
        val maxCompletionTokens: Int,
        val allowReasoningEscalation: Boolean,
        val beforeSpeech: suspend () -> Unit = {},
    )

    data class ModelTurn(
        val completion: CloudSpeechClient.ChatCompletion,
        val streamedSpeech: Boolean,
    )

    data class ToolExecution(
        val message: CloudSpeechClient.LlmMessage,
        val succeeded: Boolean,
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
        suspend fun executeTool(call: CloudSpeechClient.ToolCall): ToolExecution
        fun blockedTool(call: CloudSpeechClient.ToolCall, reason: String): CloudSpeechClient.LlmMessage
        suspend fun finishAssistant(message: CloudSpeechClient.LlmMessage, streamedSpeech: Boolean): Boolean
    }

    suspend fun run(config: Config, turnId: String = UUID.randomUUID().toString()): Outcome {
        require(config.maxToolRounds > 0) { "maxToolRounds must be positive" }
        val workingMessages = config.messages.toMutableList()
        val completedToolCallIds = mutableSetOf<String>()
        var previousToolBatchSignature: String? = null
        var playedSpeech = false
        var modelCall = 0
        var consecutiveToolFailures = 0

        eventSink(AgentEvent.AgentStarted(turnId))
        eventSink(AgentEvent.TurnStarted(turnId, config.thinkingMode))
        try {
            suspend fun requestModel(
                tools: List<CloudSpeechClient.ToolDefinition>,
            ): Pair<ModelTurn, CloudSpeechClient.LlmMessage> {
                modelCall += 1
                eventSink(AgentEvent.MessageStarted(turnId, modelCall))
                val request = CloudSpeechClient.ChatRequest(
                    messages = workingMessages,
                    tools = tools,
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
                val assistant = if (tools.isEmpty()) {
                    streamed.completion.message
                } else {
                    runtime.normalizeAssistant(streamed.completion.message)
                }
                eventSink(AgentEvent.MessageFinished(turnId, assistant))
                return streamed to assistant
            }

            suspend fun completeAssistant(
                streamed: ModelTurn,
                assistant: CloudSpeechClient.LlmMessage,
            ): Outcome.Completed {
                val finalText = assistant.content.orEmpty().trim()
                if (finalText.isBlank()) error("模型未返回最终正文")
                playedSpeech = runtime.finishAssistant(assistant, streamed.streamedSpeech) || playedSpeech
                eventSink(AgentEvent.TurnFinished(turnId, finalText))
                eventSink(AgentEvent.AgentFinished(turnId))
                return Outcome.Completed(finalText, playedSpeech)
            }

            suspend fun forceFinalSummary(reason: String): Outcome.Completed {
                workingMessages += CloudSpeechClient.LlmMessage(
                    role = "system",
                    content = buildString {
                        append("工具阶段已经结束：")
                        append(reason)
                        append("。请根据已有工具结果直接向用户总结当前进展、失败原因和下一步建议。")
                        append("不得再次调用工具，不得声称未完成的动作已经完成，回复保持简洁。")
                    },
                )
                val (streamed, assistant) = requestModel(tools = emptyList())
                if (assistant.toolCalls.isNotEmpty()) error("最终总结阶段返回了未授权工具调用")
                return completeAssistant(streamed, assistant)
            }

            repeat(config.maxToolRounds) { toolRound ->
                val tools = runtime.toolDefinitions(
                    config.allowReasoningEscalation && toolRound == 0,
                )
                val (streamed, assistant) = requestModel(tools)

                val escalation = assistant.toolCalls.firstOrNull(runtime::isReasoningEscalation)
                if (escalation != null) {
                    if (!config.allowReasoningEscalation || toolRound != 0) {
                        error("本回合深度思考升级请求无效")
                    }
                    eventSink(AgentEvent.AgentFinished(turnId))
                    return Outcome.Escalate(runtime.reasoningEscalationReason(escalation))
                }

                if (assistant.toolCalls.isEmpty()) {
                    return completeAssistant(streamed, assistant)
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
                    val execution = if (blockedReason == null) {
                        runtime.executeTool(call)
                    } else {
                        ToolExecution(runtime.blockedTool(call, blockedReason), succeeded = false)
                    }
                    consecutiveToolFailures = if (execution.succeeded) 0 else consecutiveToolFailures + 1
                    eventSink(
                        AgentEvent.ToolFinished(
                            turnId = turnId,
                            call = call,
                            result = execution.message,
                            success = execution.succeeded,
                            blocked = blockedReason != null,
                        ),
                    )
                    workingMessages += execution.message
                }
                if (consecutiveToolFailures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                    return forceFinalSummary("连续 $consecutiveToolFailures 次工具调用失败")
                }
            }
            return forceFinalSummary("已达到 ${config.maxToolRounds} 轮工具调用上限")
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

    private companion object {
        private const val MAX_CONSECUTIVE_TOOL_FAILURES = 2
    }
}
