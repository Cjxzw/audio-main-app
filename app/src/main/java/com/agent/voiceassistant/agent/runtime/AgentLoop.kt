package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

class AgentLoop(
    private val runtime: Runtime,
    private val eventSink: (AgentEvent) -> Unit = {},
) {
    data class Config(
        val messages: List<CloudSpeechClient.LlmMessage>,
        val initialThinkingMode: CloudSpeechClient.ThinkingMode,
        val maxToolRounds: Int,
        val fastMaxCompletionTokens: Int,
        val deepMaxCompletionTokens: Int,
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
        fun reasoningEscalationResult(call: CloudSpeechClient.ToolCall): ToolExecution
        fun canExecuteToolInParallel(call: CloudSpeechClient.ToolCall): Boolean
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
        var thinkingMode = config.initialThinkingMode
        var reasoningEscalated = thinkingMode == CloudSpeechClient.ThinkingMode.ENABLED

        eventSink(AgentEvent.AgentStarted(turnId))
        eventSink(AgentEvent.TurnStarted(turnId, thinkingMode))
        try {
            suspend fun requestModel(
                tools: List<CloudSpeechClient.ToolDefinition>,
            ): Pair<ModelTurn, CloudSpeechClient.LlmMessage> {
                modelCall += 1
                eventSink(AgentEvent.MessageStarted(turnId, modelCall))
                val request = CloudSpeechClient.ChatRequest(
                    messages = workingMessages,
                    tools = tools,
                    thinkingMode = thinkingMode,
                    maxCompletionTokens = if (thinkingMode == CloudSpeechClient.ThinkingMode.ENABLED) {
                        config.deepMaxCompletionTokens
                    } else {
                        config.fastMaxCompletionTokens
                    },
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
                    config.allowReasoningEscalation && !reasoningEscalated && toolRound == 0,
                )
                val (streamed, assistant) = requestModel(tools)

                val escalationCalls = assistant.toolCalls.filter(runtime::isReasoningEscalation)
                if (escalationCalls.size > 1) {
                    error("一个用户回合只能申请一次深度思考")
                }
                val escalation = escalationCalls.singleOrNull()
                if (escalation != null) {
                    if (!config.allowReasoningEscalation || reasoningEscalated || toolRound != 0) {
                        error("本回合深度思考升级请求无效")
                    }
                    reasoningEscalated = true
                    thinkingMode = CloudSpeechClient.ThinkingMode.ENABLED
                    eventSink(AgentEvent.ThinkingModeChanged(turnId, thinkingMode))
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

                data class PendingTool(
                    val call: CloudSpeechClient.ToolCall,
                    val blockedReason: String?,
                )

                val pendingTools = assistant.toolCalls.map { call ->
                    eventSink(AgentEvent.ToolStarted(turnId, call, runtime.toolDisplayName(call.name)))
                    val blockedReason = when {
                        !completedToolCallIds.add(call.id) ->
                            "重复的 tool_call_id，调用未再次执行。请基于已有结果继续。"
                        repeatedBatch ->
                            "检测到连续重复工具调用，调用已停止。请改变参数或直接总结已有结果。"
                        else -> null
                    }
                    PendingTool(call, blockedReason)
                }

                suspend fun execute(pending: PendingTool): ToolExecution {
                    val call = pending.call
                    return when {
                        pending.blockedReason != null ->
                            ToolExecution(runtime.blockedTool(call, pending.blockedReason), succeeded = false)
                        runtime.isReasoningEscalation(call) ->
                            runtime.reasoningEscalationResult(call)
                        else -> runtime.executeTool(call)
                    }
                }

                val parallelTools = pendingTools.filter { pending ->
                    pending.blockedReason == null &&
                        !runtime.isReasoningEscalation(pending.call) &&
                        runtime.canExecuteToolInParallel(pending.call)
                }
                val parallelResults = coroutineScope {
                    parallelTools.map { pending ->
                        async { pending.call.id to execute(pending) }
                    }.awaitAll().toMap()
                }
                val executions = pendingTools.map { pending ->
                    val execution = parallelResults[pending.call.id] ?: execute(pending)
                    pending to execution
                }

                for ((pending, execution) in executions) {
                    val call = pending.call
                    consecutiveToolFailures = if (execution.succeeded) 0 else consecutiveToolFailures + 1
                    eventSink(
                        AgentEvent.ToolFinished(
                            turnId = turnId,
                            call = call,
                            result = execution.message,
                            success = execution.succeeded,
                            blocked = pending.blockedReason != null,
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
        private const val MAX_CONSECUTIVE_TOOL_FAILURES = 3
    }
}
