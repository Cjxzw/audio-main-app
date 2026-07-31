package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.agent.StructuredOutputParser
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.ToolCallSafety
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
        val onContextFinalized: (String, List<CloudSpeechClient.LlmMessage>) -> Unit = { _, _ -> },
    )

    data class ModelTurn(
        val completion: CloudSpeechClient.ChatCompletion,
        val streamedSpeech: Boolean,
    )

    data class ToolExecution(
        val message: CloudSpeechClient.LlmMessage,
        val succeeded: Boolean,
    )

    data class TerminalExecution(
        val result: ToolExecution,
        val finalText: String? = null,
        val playedSpeech: Boolean = false,
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
        fun isTerminalPresentation(call: CloudSpeechClient.ToolCall): Boolean = false
        suspend fun executeTerminalPresentation(call: CloudSpeechClient.ToolCall): TerminalExecution =
            TerminalExecution(
                result = ToolExecution(
                    message = blockedTool(call, "当前运行时不支持终止型展示工具"),
                    succeeded = false,
                ),
            )
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
        suspend fun finishAssistant(
            turnId: String,
            message: CloudSpeechClient.LlmMessage,
            streamedSpeech: Boolean,
        ): Boolean
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
                        is CloudSpeechClient.ChatStreamEvent.ToolCallDelta ->
                            eventSink(AgentEvent.ToolCallDetected(turnId, streamEvent.name.orEmpty()))
                        is CloudSpeechClient.ChatStreamEvent.Finished -> Unit
                    }
                }
                playedSpeech = playedSpeech || streamed.streamedSpeech
                val assistant = runtime.normalizeAssistant(streamed.completion.message)
                return streamed to assistant
            }

            suspend fun completeAssistant(
                streamed: ModelTurn,
                assistant: CloudSpeechClient.LlmMessage,
                allowFormatRepair: Boolean = true,
                emitFinishedOnSuccess: Boolean = true,
            ): Outcome.Completed {
                val finalText = assistant.content.orEmpty().trim()
                if (finalText.isBlank()) error("模型未返回最终正文")
                val invalidFinal = assistant.toolCalls.isNotEmpty() ||
                    StructuredOutputParser.containsToolProtocol(finalText)
                if (invalidFinal) {
                    if (allowFormatRepair) {
                        workingMessages += CloudSpeechClient.LlmMessage(
                            role = "system",
                            content = buildFinalFormatRepairInstruction(),
                        )
                        val (repairedStreamed, repairedAssistant) = requestModel(
                            tools = emptyList(),
                        )
                        return completeAssistant(
                            streamed = repairedStreamed,
                            assistant = repairedAssistant,
                            allowFormatRepair = false,
                            emitFinishedOnSuccess = true,
                        )
                    }
                    val fallback = invalidFinalFallback()
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
                playedSpeech = runtime.finishAssistant(turnId, assistant, streamed.streamedSpeech) || playedSpeech
                config.onContextFinalized(turnId, workingMessages + assistant)
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
                    )
                    val invalid = assistant.toolCalls.isNotEmpty() ||
                        StructuredOutputParser.containsToolProtocol(assistant.content.orEmpty())
                    if (!invalid) {
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

                val allowedToolNames = tools.mapTo(mutableSetOf()) { it.name }
                val rejectedCalls = assistant.toolCalls.mapNotNull { call ->
                    ToolCallSafety.invalidReason(call)?.let { reason -> call to reason }
                }
                if (rejectedCalls.isNotEmpty()) {
                    rejectedCalls.forEach { (call, reason) ->
                        eventSink(
                            AgentEvent.ToolCallRejected(
                                turnId = turnId,
                                toolCallId = call.id,
                                toolName = call.name,
                                reason = reason,
                            ),
                        )
                    }
                    workingMessages += CloudSpeechClient.LlmMessage(
                        role = "system",
                        content = buildToolCallRepairInstruction(rejectedCalls.map { it.second }),
                    )
                    consecutiveToolFailureRounds += 1
                    if (consecutiveToolFailureRounds >= MAX_CONSECUTIVE_TOOL_FAILURE_ROUNDS) {
                        return forceFinalSummary("连续 $consecutiveToolFailureRounds 轮工具调用格式非法")
                    }
                    return@repeat
                }

                if (tools.isEmpty() && assistant.toolCalls.isNotEmpty()) {
                    error("当前阶段返回了未授权工具调用")
                }

                if (assistant.toolCalls.isNotEmpty()) {
                    eventSink(AgentEvent.MessageFinished(turnId, assistant))
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

                val terminalCalls = assistant.toolCalls.filter(runtime::isTerminalPresentation)
                if (terminalCalls.isNotEmpty()) {
                    workingMessages += assistant
                    if (terminalCalls.size != 1 || assistant.toolCalls.size != 1) {
                        val reason = "终止型展示工具必须单独调用，不能和其他工具同批执行。请重新输出。"
                        assistant.toolCalls.forEach { call ->
                            workingMessages += runtime.blockedTool(call, reason)
                        }
                        consecutiveToolFailureRounds += 1
                        return@repeat
                    }
                    val terminal = runtime.executeTerminalPresentation(terminalCalls.single())
                    if (terminal.result.succeeded && !terminal.finalText.isNullOrBlank()) {
                        playedSpeech = playedSpeech || terminal.playedSpeech
                        config.onContextFinalized(turnId, workingMessages + terminal.result.message)
                        eventSink(AgentEvent.TurnFinished(turnId, terminal.finalText))
                        eventSink(AgentEvent.AgentFinished(turnId))
                        return Outcome.Completed(terminal.finalText, playedSpeech)
                    }
                    workingMessages += terminal.result.message
                    consecutiveToolFailureRounds += 1
                    return@repeat
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
                    val blockedReason = when {
                        call.name !in allowedToolNames ->
                            "工具未在本轮注册，调用未执行。请改用本轮提供的工具或直接回答。"
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
                    eventSink(AgentEvent.ToolStarted(turnId, call, runtime.toolDisplayName(call.name)))
                    val execution = when {
                        pending.blockedReason != null ->
                            ToolExecution(runtime.blockedTool(call, pending.blockedReason), succeeded = false)
                        runtime.isReasoningEscalation(call) ->
                            runtime.reasoningEscalationResult(call)
                        else -> runtime.executeTool(call)
                    }
                    eventSink(
                        AgentEvent.ToolFinished(
                            turnId = turnId,
                            call = call,
                            result = execution.message,
                            success = execution.succeeded,
                            blocked = pending.blockedReason != null,
                        ),
                    )
                    return execution
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

                for ((_, execution) in executions) {
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
            append("上一条正文包含疑似伪工具调用协议，已被系统拦截。")
            append("如果意图调用工具，必须使用 API 原生 tool_calls；当前最终总结阶段不得再调用工具。")
            append("如果只是展示 JSON、XML、命令或代码，先给出简短自然语言结论，")
            append("再把不需要播报的 Markdown 详情放入 <DETAILS>...</DETAILS>。")
            append("不要在正文中输出 tool_call、function、parameter 等伪工具标签。")
        }

        private fun buildToolCallRepairInstruction(reasons: List<String>) = buildString {
            append("上一批工具调用已被系统拒绝，原因：")
            append(reasons.distinct().joinToString("；"))
            append("。请重新决定是否需要调用工具。")
            append("如需调用，只能使用本轮提供的工具名称，arguments 必须是完整的 JSON 对象。")
            append("不要复述或继续刚才的非法工具调用。")
        }

        private fun invalidFinalFallback() = CloudSpeechClient.LlmMessage(
            role = "assistant",
            content = "当前回复包含未受支持的工具调用格式，相关内容已被拦截。请稍后让我重试。",
        )
    }
}
