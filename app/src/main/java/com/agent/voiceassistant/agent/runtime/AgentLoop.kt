package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.agent.StructuredOutputParser
import com.agent.voiceassistant.agent.SpokenReplyPolicy
import com.agent.voiceassistant.cloud.CloudSpeechClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineStart
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
        val automaticReasoningToolThreshold: Int = DEFAULT_AUTOMATIC_REASONING_TOOL_THRESHOLD,
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
        fun onReasoningEscalation(reason: String) = Unit
        fun reasoningEscalationResult(call: CloudSpeechClient.ToolCall): ToolExecution
        fun countsTowardAutomaticReasoning(call: CloudSpeechClient.ToolCall): Boolean =
            !isReasoningEscalation(call)
        suspend fun onAutomaticReasoningEscalation(
            toolCallCount: Int,
            triggerCalls: List<CloudSpeechClient.ToolCall>,
        ) = Unit
        fun canExecuteToolInParallel(call: CloudSpeechClient.ToolCall): Boolean
        fun toolDisplayName(toolName: String): String
        suspend fun executeTool(call: CloudSpeechClient.ToolCall): ToolExecution
        fun blockedTool(call: CloudSpeechClient.ToolCall, reason: String): CloudSpeechClient.LlmMessage
        suspend fun finishAssistant(message: CloudSpeechClient.LlmMessage, streamedSpeech: Boolean): Boolean
    }

    suspend fun run(config: Config, turnId: String = UUID.randomUUID().toString()): Outcome {
        require(config.maxToolRounds > 0) { "maxToolRounds must be positive" }
        require(config.automaticReasoningToolThreshold > 0) {
            "automaticReasoningToolThreshold must be positive"
        }
        val workingMessages = config.messages.toMutableList()
        val completedToolCallIds = mutableSetOf<String>()
        var previousToolBatchSignature: String? = null
        var playedSpeech = false
        var modelCall = 0
        var consecutiveToolFailureRounds = 0
        var thinkingMode = config.initialThinkingMode
        var reasoningEscalated = thinkingMode == CloudSpeechClient.ThinkingMode.ENABLED
        var businessToolCallCount = 0

        eventSink(AgentEvent.AgentStarted(turnId))
        eventSink(AgentEvent.TurnStarted(turnId, thinkingMode))
        try {
            suspend fun requestModel(
                tools: List<CloudSpeechClient.ToolDefinition>,
                emitFinishedEvent: Boolean = true,
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
                val assistant = runtime.normalizeAssistant(streamed.completion.message)
                if (emitFinishedEvent && !(tools.isEmpty() && assistant.toolCalls.isNotEmpty())) {
                    eventSink(AgentEvent.MessageFinished(turnId, assistant))
                }
                return streamed to assistant
            }

            suspend fun completeAssistant(
                streamed: ModelTurn,
                assistant: CloudSpeechClient.LlmMessage,
                allowFormatRepair: Boolean = true,
                emitFinishedOnSuccess: Boolean = false,
            ): Outcome.Completed {
                val finalText = assistant.content.orEmpty().trim()
                if (finalText.isBlank()) error("模型未返回最终正文")
                val invalidFinal = assistant.toolCalls.isNotEmpty() ||
                    StructuredOutputParser.containsToolProtocol(finalText) ||
                    SpokenReplyPolicy.hasUnsupportedUnfencedStructure(finalText) ||
                    SpokenReplyPolicy.isDetailsOnly(finalText)
                if (invalidFinal) {
                    if (allowFormatRepair) {
                        workingMessages += CloudSpeechClient.LlmMessage(
                            role = "system",
                            content = buildFinalFormatRepairInstruction(),
                        )
                        val (repairedStreamed, repairedAssistant) = requestModel(
                            tools = emptyList(),
                            emitFinishedEvent = false,
                        )
                        return completeAssistant(
                            streamed = repairedStreamed,
                            assistant = repairedAssistant,
                            allowFormatRepair = false,
                            emitFinishedOnSuccess = true,
                        )
                    }
                    val fallback = invalidFinalFallback()
                    eventSink(AgentEvent.MessageFinished(turnId, fallback))
                    return completeAssistant(
                        streamed = ModelTurn(
                            completion = CloudSpeechClient.ChatCompletion(fallback, "format_guard"),
                            streamedSpeech = false,
                        ),
                        assistant = fallback,
                        allowFormatRepair = false,
                    )
                }
                if (emitFinishedOnSuccess) {
                    eventSink(AgentEvent.MessageFinished(turnId, assistant))
                }
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
                repeat(MAX_FINAL_PROTOCOL_ATTEMPTS) { attempt ->
                    val (streamed, assistant) = requestModel(
                        tools = emptyList(),
                        emitFinishedEvent = false,
                    )
                    val invalid = assistant.toolCalls.isNotEmpty() ||
                        StructuredOutputParser.containsToolProtocol(assistant.content.orEmpty()) ||
                        SpokenReplyPolicy.hasUnsupportedUnfencedStructure(assistant.content.orEmpty()) ||
                        SpokenReplyPolicy.isDetailsOnly(assistant.content.orEmpty())
                    if (!invalid) {
                        eventSink(AgentEvent.MessageFinished(turnId, assistant))
                        return completeAssistant(streamed, assistant)
                    }
                    workingMessages += CloudSpeechClient.LlmMessage(
                        role = "system",
                        content = buildString {
                            append(buildFinalFormatRepairInstruction())
                            if (attempt + 1 == MAX_FINAL_PROTOCOL_ATTEMPTS) {
                                append("这是最后一次格式修正机会。")
                            }
                        },
                    )
                }

                val fallback = invalidFinalFallback()
                eventSink(AgentEvent.MessageFinished(turnId, fallback))
                return completeAssistant(
                    streamed = ModelTurn(
                        completion = CloudSpeechClient.ChatCompletion(fallback, "protocol_guard"),
                        streamedSpeech = false,
                    ),
                    assistant = fallback,
                )
            }

            repeat(config.maxToolRounds) { toolRound ->
                val tools = runtime.toolDefinitions(
                    config.allowReasoningEscalation && !reasoningEscalated && toolRound == 0,
                )
                val (streamed, assistant) = requestModel(tools)

                if (tools.isEmpty() && assistant.toolCalls.isNotEmpty()) {
                    error("当前阶段返回了未授权工具调用")
                }

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
                    runtime.onReasoningEscalation(runtime.reasoningEscalationReason(escalation))
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

                val countableTools = pendingTools.filter { pending ->
                    pending.blockedReason == null &&
                        !runtime.isReasoningEscalation(pending.call) &&
                        runtime.countsTowardAutomaticReasoning(pending.call)
                }
                val previousToolCallCount = businessToolCallCount
                businessToolCallCount += countableTools.size
                val automaticallyEscalated =
                    config.allowReasoningEscalation &&
                        !reasoningEscalated &&
                        thinkingMode == CloudSpeechClient.ThinkingMode.DISABLED &&
                        previousToolCallCount < config.automaticReasoningToolThreshold &&
                        businessToolCallCount >= config.automaticReasoningToolThreshold
                if (automaticallyEscalated) {
                    reasoningEscalated = true
                    thinkingMode = CloudSpeechClient.ThinkingMode.ENABLED
                    eventSink(AgentEvent.ThinkingModeChanged(turnId, thinkingMode))
                    eventSink(
                        AgentEvent.AutomaticThinkingEscalated(
                            turnId = turnId,
                            toolCallCount = businessToolCallCount,
                            triggerCalls = countableTools.map { it.call },
                        ),
                    )
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
                if (parallelTools.size > 1) {
                    eventSink(
                        AgentEvent.ParallelToolsStarted(
                            turnId = turnId,
                            calls = parallelTools.map { it.call },
                        ),
                    )
                }
                val executions = coroutineScope {
                    val feedback = if (automaticallyEscalated) {
                        async(start = CoroutineStart.UNDISPATCHED) {
                            runtime.onAutomaticReasoningEscalation(
                                toolCallCount = businessToolCallCount,
                                triggerCalls = countableTools.map { it.call },
                            )
                        }
                    } else {
                        null
                    }
                    val parallelResults = parallelTools.map { pending ->
                        async { pending.call.id to execute(pending) }
                    }.awaitAll().toMap()
                    val results = pendingTools.map { pending ->
                        val execution = parallelResults[pending.call.id] ?: execute(pending)
                        pending to execution
                    }
                    feedback?.await()
                    results
                }

                for ((pending, execution) in executions) {
                    val call = pending.call
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
                consecutiveToolFailureRounds = when {
                    executions.any { (_, execution) -> execution.succeeded } -> 0
                    executions.any { (_, execution) -> !execution.succeeded } ->
                        consecutiveToolFailureRounds + 1
                    else -> consecutiveToolFailureRounds
                }
                if (consecutiveToolFailureRounds >= MAX_CONSECUTIVE_TOOL_FAILURE_ROUNDS) {
                    return forceFinalSummary("连续 $consecutiveToolFailureRounds 轮工具调用失败")
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
        private const val MAX_CONSECUTIVE_TOOL_FAILURE_ROUNDS = 3
        private const val MAX_FINAL_PROTOCOL_ATTEMPTS = 2
        private const val DEFAULT_AUTOMATIC_REASONING_TOOL_THRESHOLD = 3

        private fun buildFinalFormatRepairInstruction() = buildString {
            append("上一条正文格式不受支持，已被系统拦截。")
            append("如果意图调用工具，必须使用 API 原生 tool_calls；当前最终总结阶段不得再调用工具。")
            append("如果只是展示 JSON、XML、命令或代码，必须放入 Markdown 三反引号围栏，")
            append("并在围栏外提供至少一句无 Markdown 的简短口语总结。")
            append("不要只返回代码块，也不要输出裸露的 XML、JSON、工具标签或解释规则。")
        }

        private fun invalidFinalFallback() = CloudSpeechClient.LlmMessage(
            role = "assistant",
            content = "当前回复包含未受支持的工具调用格式，相关内容已被拦截。请稍后让我重试。",
        )
    }
}
