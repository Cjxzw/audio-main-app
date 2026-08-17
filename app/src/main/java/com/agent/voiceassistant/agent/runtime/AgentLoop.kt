package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.agent.StructuredOutputParser
import com.agent.voiceassistant.agent.BodyToolCall
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.ToolCallSafety
import com.agent.voiceassistant.cloud.NetworkTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

class AgentLoop(
    private val runtime: Runtime,
    private val eventSink: (AgentEvent) -> Unit = {},
) {
    enum class CheckpointPhase { RUNNING, WAITING_NETWORK, WAITING_RECOVERY }

    data class Config(
        val messages: List<CloudSpeechClient.LlmMessage>,
        val initialThinkingMode: CloudSpeechClient.ThinkingMode,
        val maxToolRounds: Int,
        val fastMaxCompletionTokens: Int,
        val deepMaxCompletionTokens: Int,
        val allowReasoningEscalation: Boolean,
        val automaticReasoningToolThreshold: Int = DEFAULT_AUTOMATIC_REASONING_TOOL_THRESHOLD,
        val activeToolBudgetMs: Long = DEFAULT_ACTIVE_TOOL_BUDGET_MS,
        val enableBodyToolAdapter: Boolean = false,
        val enableModelFormatRepair: Boolean = true,
        val enableEmptyFinalRetry: Boolean = true,
        val enableStreamIntegrityRetry: Boolean = true,
        val enableActiveToolBudget: Boolean = true,
        val enableForcedFinalSummary: Boolean = true,
        val enableFinalResponseFormatRepair: Boolean = false,
        val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        val initialBusinessToolCallCount: Int = 0,
        val initialActiveElapsedMs: Long = 0,
        val initialActiveBudgetStarted: Boolean = false,
        val beforeSpeech: suspend () -> Unit = {},
        val onContextFinalized: (String, List<CloudSpeechClient.LlmMessage>) -> Unit = { _, _ -> },
        val onCheckpoint: (
            turnId: String,
            messages: List<CloudSpeechClient.LlmMessage>,
            thinkingMode: CloudSpeechClient.ThinkingMode,
            businessToolCallCount: Int,
            activeElapsedMs: Long,
            activeBudgetStarted: Boolean,
            phase: CheckpointPhase,
        ) -> Unit = { _, _, _, _, _, _, _ -> },
        val onTurnCompleted: (String) -> Unit = {},
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
        fun isToolAllowed(call: CloudSpeechClient.ToolCall, nativeToolNames: Set<String>): Boolean =
            call.name in nativeToolNames
        fun adaptBodyToolCall(call: BodyToolCall, callId: String): CloudSpeechClient.ToolCall? =
            CloudSpeechClient.ToolCall(callId, call.name, call.arguments.toString())
        suspend fun awaitRecovery(reason: String, networkTimeout: Boolean): String = ""

        suspend fun modelTurn(
            request: CloudSpeechClient.ChatRequest,
            beforeSpeech: suspend () -> Unit,
            onStreamEvent: (CloudSpeechClient.ChatStreamEvent) -> Unit,
        ): ModelTurn

        fun normalizeAssistant(message: CloudSpeechClient.LlmMessage): CloudSpeechClient.LlmMessage
        fun hasUsableFinalResponse(message: CloudSpeechClient.LlmMessage): Boolean =
            !message.content.isNullOrBlank()

        fun finalResponseFormatRepairInstruction(): String =
            "上一条回复格式不完整，无法提取给用户的正文。请重新返回完整格式，并确保 <answer>...</answer> 中包含最终回复正文；不要解释本次修正。"
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
        fun isDelegation(call: CloudSpeechClient.ToolCall): Boolean = false
        suspend fun beforeToolBatch(
            calls: List<CloudSpeechClient.ToolCall>,
            businessToolCallCount: Int,
        ): ToolGateDecision = ToolGateDecision()
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
        require(config.activeToolBudgetMs > 0) { "activeToolBudgetMs must be positive" }
        val workingMessages = config.messages.toMutableList()
        val completedToolCallIds = mutableSetOf<String>()
        var previousToolBatchSignature: String? = null
        var playedSpeech = false
        var modelCall = 0
        var consecutiveToolFailureRounds = 0
        var thinkingMode = config.initialThinkingMode
        var businessToolCallCount = config.initialBusinessToolCallCount
        var activeElapsedMs = config.initialActiveElapsedMs
        var activeSinceMs: Long? = null
        var activeBudgetStarted = config.initialActiveBudgetStarted
        var activeBudgetBlocked = false

        fun pauseActiveBudget() {
            val started = activeSinceMs ?: return
            activeElapsedMs += (config.monotonicNowMs() - started).coerceAtLeast(0L)
            activeSinceMs = null
        }

        fun resumeActiveBudget() {
            if (activeSinceMs != null) return
            activeSinceMs = config.monotonicNowMs()
            if (!activeBudgetStarted) {
                activeBudgetStarted = true
                eventSink(AgentEvent.ActiveBudgetStarted(turnId))
            }
        }

        fun currentActiveElapsedMs(): Long = activeElapsedMs +
            (activeSinceMs?.let { (config.monotonicNowMs() - it).coerceAtLeast(0L) } ?: 0L)

        fun checkpoint(phase: CheckpointPhase) {
            config.onCheckpoint(
                turnId,
                workingMessages.toList(),
                thinkingMode,
                businessToolCallCount,
                currentActiveElapsedMs(),
                activeBudgetStarted,
                phase,
            )
        }

        suspend fun finishLocalFailure(text: String): Outcome.Completed {
            val assistant = CloudSpeechClient.LlmMessage(role = "assistant", content = text)
            eventSink(AgentEvent.MessageFinished(turnId, assistant))
            runCatching { playedSpeech = runtime.finishAssistant(turnId, assistant, streamedSpeech = false) || playedSpeech }
            config.onContextFinalized(turnId, workingMessages + assistant)
            config.onTurnCompleted(turnId)
            eventSink(AgentEvent.TurnFinished(turnId, text))
            eventSink(AgentEvent.AgentFinished(turnId))
            return Outcome.Completed(text, playedSpeech)
        }

        eventSink(AgentEvent.AgentStarted(turnId))
        eventSink(AgentEvent.TurnStarted(turnId, thinkingMode))
        checkpoint(CheckpointPhase.RUNNING)
        try {
            suspend fun requestModel(
                tools: List<CloudSpeechClient.ToolDefinition>,
                maxCompletionTokens: Int? = null,
            ): Pair<ModelTurn, CloudSpeechClient.LlmMessage> {
                var streamedResult: ModelTurn? = null
                var integrityRetry = 0
                var partialContent = ""
                var partialReasoning = ""
                while (streamedResult == null) {
                    pauseActiveBudget()
                    modelCall += 1
                    eventSink(AgentEvent.MessageStarted(turnId, modelCall))
                    val request = CloudSpeechClient.ChatRequest(
                        messages = workingMessages.toList(),
                        tools = tools,
                        thinkingMode = thinkingMode,
                        maxCompletionTokens = maxCompletionTokens ?: if (thinkingMode == CloudSpeechClient.ThinkingMode.ENABLED) {
                            config.deepMaxCompletionTokens
                        } else {
                            config.fastMaxCompletionTokens
                        },
                    )
                    try {
                        val candidate = runtime.modelTurn(request, config.beforeSpeech) { streamEvent ->
                            when (streamEvent) {
                                is CloudSpeechClient.ChatStreamEvent.ContentDelta -> {
                                    resumeActiveBudget()
                                    eventSink(AgentEvent.ContentDelta(turnId, streamEvent.text, modelCall = modelCall))
                                }
                                is CloudSpeechClient.ChatStreamEvent.ReasoningDelta -> {
                                    resumeActiveBudget()
                                    eventSink(AgentEvent.ReasoningDelta(turnId, streamEvent.text, modelCall = modelCall))
                                }
                                is CloudSpeechClient.ChatStreamEvent.ToolCallDelta -> {
                                    resumeActiveBudget()
                                    eventSink(AgentEvent.ToolCallDetected(turnId, streamEvent.name.orEmpty(), modelCall))
                                }
                                is CloudSpeechClient.ChatStreamEvent.Finished -> Unit
                            }
                        }
                        val completion = candidate.completion
                        val diagnostics = completion.streamDiagnostics
                        val blank = completion.message.content.isNullOrBlank() && completion.message.toolCalls.isEmpty()
                        val truncated = completion.finishReason == "length"
                        val brokenStream = diagnostics.protocolObserved && (
                            !diagnostics.hasNormalEnd || diagnostics.malformedEventCount > 0
                            )
                        if (config.enableStreamIntegrityRetry && diagnostics.protocolObserved &&
                            (blank || truncated || brokenStream) &&
                            integrityRetry < MAX_STREAM_INTEGRITY_RETRIES
                        ) {
                            integrityRetry += 1
                            partialContent += completion.message.content.orEmpty()
                            partialReasoning += completion.message.reasoningContent.orEmpty()
                            if (partialContent.isNotBlank()) {
                                workingMessages += CloudSpeechClient.LlmMessage(
                                    role = "assistant",
                                    content = partialContent,
                                )
                            }
                            workingMessages += CloudSpeechClient.LlmMessage(
                                role = "system",
                                content = if (partialContent.isBlank()) {
                                    "上一请求只产生了思考或空响应。关闭深度思考，直接生成完整正文，不要解释重试过程。"
                                } else {
                                    "上一响应未正常结束。请从中断处继续，禁止重复已有内容，并完整结束回答。"
                                },
                            )
                            thinkingMode = CloudSpeechClient.ThinkingMode.DISABLED
                            eventSink(AgentEvent.ThinkingModeChanged(turnId, thinkingMode))
                            continue
                        }
                        val mergedMessage = completion.message.copy(
                            content = partialContent + completion.message.content.orEmpty(),
                            reasoningContent = (partialReasoning + completion.message.reasoningContent.orEmpty())
                                .takeIf(String::isNotEmpty),
                        )
                        streamedResult = candidate.copy(
                            completion = completion.copy(message = mergedMessage),
                        )
                        if (activeSinceMs == null) resumeActiveBudget()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        checkpoint(
                            if (error is NetworkTimeoutException) {
                                CheckpointPhase.WAITING_NETWORK
                            } else {
                                CheckpointPhase.WAITING_RECOVERY
                            },
                        )
                        val retryInput = runtime.awaitRecovery(
                            error.message ?: error.javaClass.simpleName,
                            error is NetworkTimeoutException,
                        )
                        if (retryInput.isNotBlank()) {
                            workingMessages += CloudSpeechClient.LlmMessage("user", retryInput)
                        }
                    }
                }
                val streamed = requireNotNull(streamedResult)
                playedSpeech = playedSpeech || streamed.streamedSpeech
                val completion = streamed.completion
                val usage = completion.usage
                val estimatedPromptTokens = workingMessages.sumOf {
                    it.content.orEmpty().length + it.reasoningContent.orEmpty().length
                }.toLong() / 3L
                var assistant = runtime.normalizeAssistant(completion.message).copy(
                    responseMetadata = CloudSpeechClient.ResponseMetadata(
                        modelId = completion.modelId,
                        promptTokens = usage?.promptTokens ?: estimatedPromptTokens,
                        completionTokens = usage?.completionTokens,
                        reasoningTokens = usage?.reasoningTokens,
                        contextWindowTokens = contextWindowFor(completion.modelId),
                        promptTokensEstimated = usage?.promptTokens == null,
                        finishReason = completion.finishReason,
                        streamComplete = completion.streamDiagnostics.hasNormalEnd &&
                            completion.streamDiagnostics.malformedEventCount == 0,
                    ),
                )
                if (config.enableBodyToolAdapter && assistant.toolCalls.isEmpty()) {
                    val availableNames = tools.mapTo(mutableSetOf()) { it.name }
                    val matches = StructuredOutputParser.findBodyToolCallMatches(assistant.content.orEmpty())
                    val adapted = matches.mapIndexedNotNull { index, match ->
                        runtime.adaptBodyToolCall(match.call, "body-${turnId.take(8)}-$modelCall-$index")
                            ?.takeIf { runtime.isToolAllowed(it, availableNames) }
                            ?.let { match to it }
                    }
                    if (adapted.isNotEmpty()) {
                        assistant = assistant.copy(
                            content = StructuredOutputParser.withoutBodyToolPayloads(
                                assistant.content.orEmpty(),
                                adapted.map { it.first },
                            ),
                            toolCalls = adapted.map { it.second },
                        )
                    }
                }
                eventSink(AgentEvent.ModelResponseFinished(turnId, assistant, modelCall))
                return streamed to assistant
            }

            suspend fun completeAssistant(
                streamed: ModelTurn,
                assistant: CloudSpeechClient.LlmMessage,
                allowFormatRepair: Boolean = true,
                emitFinishedOnSuccess: Boolean = true,
                emptyFinalRetriesRemaining: Int = MAX_EMPTY_FINAL_RETRIES,
                retryMaxCompletionTokens: Int? = null,
            ): Outcome.Completed {
                val finalText = assistant.content.orEmpty().trim()
                if (finalText.isBlank()) {
                    if (config.enableFinalResponseFormatRepair && allowFormatRepair) {
                        workingMessages += CloudSpeechClient.LlmMessage(
                            role = "system",
                            content = runtime.finalResponseFormatRepairInstruction(),
                        )
                        val (repairedStreamed, repairedAssistant) = requestModel(
                            tools = emptyList(),
                            maxCompletionTokens = retryMaxCompletionTokens,
                        )
                        return completeAssistant(
                            streamed = repairedStreamed,
                            assistant = repairedAssistant,
                            allowFormatRepair = false,
                            emitFinishedOnSuccess = emitFinishedOnSuccess,
                            emptyFinalRetriesRemaining = emptyFinalRetriesRemaining,
                            retryMaxCompletionTokens = retryMaxCompletionTokens,
                        )
                    }
                    if (config.enableEmptyFinalRetry && emptyFinalRetriesRemaining > 0) {
                        val attempt = MAX_EMPTY_FINAL_RETRIES - emptyFinalRetriesRemaining + 1
                        eventSink(
                            AgentEvent.FinalResponseRetry(
                                turnId = turnId,
                                attempt = attempt,
                                maxRetries = MAX_EMPTY_FINAL_RETRIES,
                            ),
                        )
                        workingMessages += CloudSpeechClient.LlmMessage(
                            role = "system",
                            content = buildEmptyFinalRetryInstruction(),
                        )
                        val (retryStreamed, retryAssistant) = requestModel(
                            tools = runtime.toolDefinitions(config.allowReasoningEscalation),
                            maxCompletionTokens = retryMaxCompletionTokens,
                        )
                        return completeAssistant(
                            streamed = retryStreamed,
                            assistant = retryAssistant,
                            allowFormatRepair = allowFormatRepair,
                            emitFinishedOnSuccess = emitFinishedOnSuccess,
                            emptyFinalRetriesRemaining = emptyFinalRetriesRemaining - 1,
                            retryMaxCompletionTokens = retryMaxCompletionTokens,
                        )
                    }
                    return finishLocalFailure("这次没有生成可用回复，请再试一次。")
                }
                val invalidFinal = assistant.toolCalls.isNotEmpty() || (
                    config.enableModelFormatRepair && StructuredOutputParser.containsToolProtocol(finalText)
                    )
                if (invalidFinal) {
                    if (config.enableModelFormatRepair && allowFormatRepair) {
                        workingMessages += CloudSpeechClient.LlmMessage(
                            role = "system",
                            content = buildFinalFormatRepairInstruction(),
                        )
                        val (repairedStreamed, repairedAssistant) = requestModel(
                            tools = runtime.toolDefinitions(config.allowReasoningEscalation),
                        )
                        return completeAssistant(
                            streamed = repairedStreamed,
                            assistant = repairedAssistant,
                            allowFormatRepair = false,
                            emitFinishedOnSuccess = true,
                            retryMaxCompletionTokens = retryMaxCompletionTokens,
                        )
                    }
                    val retryInput = runtime.awaitRecovery("模型连续返回非正文协议", networkTimeout = false)
                    if (retryInput.isNotBlank()) workingMessages += CloudSpeechClient.LlmMessage("user", retryInput)
                    workingMessages += CloudSpeechClient.LlmMessage("system", buildFinalFormatRepairInstruction())
                    val (retryStreamed, retryAssistant) = requestModel(runtime.toolDefinitions(config.allowReasoningEscalation))
                    return completeAssistant(retryStreamed, retryAssistant, allowFormatRepair = false)
                }
                if (!runtime.hasUsableFinalResponse(assistant)) {
                    if (config.enableFinalResponseFormatRepair && allowFormatRepair) {
                        workingMessages += assistant.copy(responseMetadata = null)
                        workingMessages += CloudSpeechClient.LlmMessage(
                            role = "system",
                            content = runtime.finalResponseFormatRepairInstruction(),
                        )
                        val (repairedStreamed, repairedAssistant) = requestModel(
                            tools = emptyList(),
                            maxCompletionTokens = retryMaxCompletionTokens,
                        )
                        return completeAssistant(
                            streamed = repairedStreamed,
                            assistant = repairedAssistant,
                            allowFormatRepair = false,
                            emitFinishedOnSuccess = emitFinishedOnSuccess,
                            emptyFinalRetriesRemaining = emptyFinalRetriesRemaining,
                            retryMaxCompletionTokens = retryMaxCompletionTokens,
                        )
                    }
                    return finishLocalFailure("这次回复格式不完整，请再试一次。")
                }
                if (emitFinishedOnSuccess) {
                    eventSink(AgentEvent.MessageFinished(turnId, assistant))
                }
                while (true) {
                    try {
                        playedSpeech = runtime.finishAssistant(turnId, assistant, streamed.streamedSpeech) || playedSpeech
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        val retryInput = runtime.awaitRecovery(
                            error.message ?: error.javaClass.simpleName,
                            error is NetworkTimeoutException,
                        )
                        if (retryInput.isNotBlank()) workingMessages += CloudSpeechClient.LlmMessage("user", retryInput)
                    }
                }
                config.onContextFinalized(turnId, workingMessages + assistant)
                config.onTurnCompleted(turnId)
                eventSink(AgentEvent.TurnFinished(turnId, finalText))
                eventSink(AgentEvent.AgentFinished(turnId))
                return Outcome.Completed(finalText, playedSpeech)
            }

            suspend fun forceFinalSummary(reason: String): Outcome.Completed {
                if (!config.enableForcedFinalSummary) {
                    return finishLocalFailure("本回合未完成：$reason。")
                }
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
                        tools = runtime.toolDefinitions(config.allowReasoningEscalation),
                        maxCompletionTokens = FINAL_SUMMARY_MAX_COMPLETION_TOKENS,
                    )
                    val invalid = assistant.toolCalls.isNotEmpty() ||
                        StructuredOutputParser.containsToolProtocol(assistant.content.orEmpty())
                    if (!invalid) {
                        return completeAssistant(
                            streamed = streamed,
                            assistant = assistant,
                            retryMaxCompletionTokens = FINAL_SUMMARY_MAX_COMPLETION_TOKENS,
                        )
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

                return finishLocalFailure("本回合未完成：模型未能生成可用总结。请重新发送请求。")
            }

            repeat(config.maxToolRounds) { toolRound ->
                val tools = runtime.toolDefinitions(config.allowReasoningEscalation)
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
                    val terminalCall = terminalCalls.single()
                    eventSink(AgentEvent.ToolStarted(turnId, terminalCall, runtime.toolDisplayName(terminalCall.name)))
                    val terminal = runtime.executeTerminalPresentation(terminalCall)
                    eventSink(
                        AgentEvent.ToolFinished(
                            turnId = turnId,
                            call = terminalCall,
                            result = terminal.result.message,
                            success = terminal.result.succeeded,
                        ),
                    )
                    if (terminal.result.succeeded && !terminal.finalText.isNullOrBlank()) {
                        playedSpeech = playedSpeech || terminal.playedSpeech
                        config.onContextFinalized(turnId, workingMessages + terminal.result.message)
                        config.onTurnCompleted(turnId)
                        eventSink(AgentEvent.TurnFinished(turnId, terminal.finalText))
                        eventSink(AgentEvent.AgentFinished(turnId))
                        return Outcome.Completed(terminal.finalText, playedSpeech)
                    }
                    workingMessages += terminal.result.message
                    consecutiveToolFailureRounds += 1
                    return@repeat
                }

                workingMessages += assistant
                checkpoint(CheckpointPhase.RUNNING)
                val batchSignature = assistant.toolCalls.joinToString("|") { call ->
                    "${call.name}:${call.arguments.trim()}"
                }
                val repeatedBatch = batchSignature == previousToolBatchSignature
                previousToolBatchSignature = batchSignature

                data class PendingTool(
                    val call: CloudSpeechClient.ToolCall,
                    val blockedReason: String?,
                )

                val projectedBusinessToolCallCount = businessToolCallCount + assistant.toolCalls.count {
                    !runtime.isReasoningEscalation(it) && runtime.countsTowardAutomaticReasoning(it)
                }
                val routeGate = runtime.beforeToolBatch(
                    calls = assistant.toolCalls,
                    businessToolCallCount = projectedBusinessToolCallCount,
                )
                val pendingTools = assistant.toolCalls.map { call ->
                    val overActiveBudget = config.enableActiveToolBudget && activeBudgetStarted &&
                        currentActiveElapsedMs() >= config.activeToolBudgetMs &&
                        !runtime.isDelegation(call)
                    val blockedReason = when {
                        !runtime.isToolAllowed(call, allowedToolNames) ->
                            "工具未在本轮注册，调用未执行。请改用本轮提供的工具或直接回答。"
                        !completedToolCallIds.add(call.id) ->
                            "重复的 tool_call_id，调用未再次执行。请基于已有结果继续。"
                        repeatedBatch ->
                            "检测到连续重复工具调用，调用已停止。请改变参数或直接总结已有结果。"
                        call.id in routeGate.blockedCallIds ->
                            "意图路由已要求停止新增本地工具并优先任务委派；本次调用未执行。"
                        overActiveBudget -> ACTIVE_TOOL_BUDGET_BLOCK_MESSAGE
                        else -> null
                    }
                    PendingTool(call, blockedReason)
                }

                val countableTools = pendingTools.filter { pending ->
                    pending.blockedReason == null &&
                        !runtime.isReasoningEscalation(pending.call) &&
                        runtime.countsTowardAutomaticReasoning(pending.call)
                }
                businessToolCallCount += countableTools.size
                if (!routeGate.prompt.isNullOrBlank()) {
                    workingMessages += CloudSpeechClient.LlmMessage(
                        role = "system",
                        content = routeGate.prompt,
                    )
                }

                suspend fun execute(pending: PendingTool): ToolExecution {
                    val call = pending.call
                    eventSink(AgentEvent.ToolStarted(turnId, call, runtime.toolDisplayName(call.name)))
                    val activeBeforeExecution = currentActiveElapsedMs()
                    val execution = try {
                        when {
                            pending.blockedReason != null ->
                                ToolExecution(runtime.blockedTool(call, pending.blockedReason), succeeded = false)
                            runtime.isReasoningEscalation(call) ->
                                runtime.reasoningEscalationResult(call)
                            else -> runtime.executeTool(call)
                        }
                    } catch (error: NetworkTimeoutException) {
                        pauseActiveBudget()
                        activeElapsedMs = activeBeforeExecution
                        throw error
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
                    val parallelResults = parallelTools.map { pending ->
                        async { pending.call.id to execute(pending) }
                    }.awaitAll().toMap()
                    val results = pendingTools.map { pending ->
                        val execution = parallelResults[pending.call.id] ?: execute(pending)
                        pending to execution
                    }
                    results
                }

                for ((_, execution) in executions) {
                    workingMessages += execution.message
                }
                val budgetBlockedThisRound = pendingTools.any { pending ->
                    pending.blockedReason == ACTIVE_TOOL_BUDGET_BLOCK_MESSAGE
                }
                if (budgetBlockedThisRound) {
                    if (!activeBudgetBlocked) {
                        activeBudgetBlocked = true
                        eventSink(
                            AgentEvent.ActiveToolBudgetExceeded(
                                turnId = turnId,
                                activeElapsedMs = currentActiveElapsedMs(),
                                blockedCalls = pendingTools
                                    .filter { it.blockedReason == ACTIVE_TOOL_BUDGET_BLOCK_MESSAGE }
                                    .map { it.call },
                            ),
                        )
                    }
                    workingMessages += CloudSpeechClient.LlmMessage(
                        role = "system",
                        content = ACTIVE_TOOL_BUDGET_PROMPT,
                    )
                }
                checkpoint(CheckpointPhase.RUNNING)
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
            if (error is CancellationException) throw error
            pauseActiveBudget()
            checkpoint(
                if (error is NetworkTimeoutException) {
                    CheckpointPhase.WAITING_NETWORK
                } else {
                    CheckpointPhase.WAITING_RECOVERY
                },
            )
            eventSink(
                AgentEvent.AgentFailed(
                    turnId = turnId,
                    error = error.message ?: error.javaClass.simpleName,
                ),
            )
            return finishLocalFailure("本回合未完成：${error.message ?: error.javaClass.simpleName}。请重新发送请求。")
        }
    }

    private companion object {
        const val MAX_STREAM_INTEGRITY_RETRIES = 1

        fun contextWindowFor(modelId: String?): Long? = when (modelId) {
            "mimo-v2.5-pro" -> 1_000_000L
            else -> null
        }
        private const val MAX_CONSECUTIVE_TOOL_FAILURE_ROUNDS = 3
        private const val MAX_FINAL_PROTOCOL_ATTEMPTS = 2
        private const val MAX_EMPTY_FINAL_RETRIES = 2
        private const val FINAL_SUMMARY_MAX_COMPLETION_TOKENS = 256
        private const val DEFAULT_AUTOMATIC_REASONING_TOOL_THRESHOLD = 3
        private const val DEFAULT_ACTIVE_TOOL_BUDGET_MS = 30_000L
        private const val ACTIVE_TOOL_BUDGET_BLOCK_MESSAGE =
            "当前回合的有效任务耗时已超过 30 秒，本地工具调用未执行。请立即总结已有结果，或调用 hub_dispatch_task 委派任务。"
        private val ACTIVE_TOOL_BUDGET_PROMPT = """
            当前回合的有效任务耗时已超过 30 秒，系统不再允许继续扩展本地工具调用。请依据已有结果立即形成简洁总结；如果任务仍未完成，应调用 hub_dispatch_task，将任务委派给路由表中最适合的执行器。除任务委派外，不得再发起新的工具调用。Hub 没有合适执行器时，请根据已有结果总结并说明限制。
        """.trimIndent()

        private fun buildFinalFormatRepairInstruction() = buildString {
            append("上一条正文包含疑似伪工具调用协议，已被系统拦截。")
            append("如果意图调用工具，必须使用 API 原生 tool_calls；当前最终总结阶段不得再调用工具。")
            append("如果只是展示 JSON、XML、命令或代码，先给出简短自然语言结论，")
            append("再把不需要播报的 Markdown 详情放入 <DETAILS>...</DETAILS>。")
            append("不要在正文中输出 tool_call、function、parameter 等伪工具标签。")
        }

        private fun buildEmptyFinalRetryInstruction() =
            "上一条模型响应没有可用的最终正文。请继续完成当前请求，直接输出给用户的自然语言结论；不要返回空内容，也不要解释这条重试指令。"

        private fun buildToolCallRepairInstruction(reasons: List<String>) = buildString {
            append("上一批工具调用已被系统拒绝，原因：")
            append(reasons.distinct().joinToString("；"))
            append("。请重新决定是否需要调用工具。")
            append("如需调用，只能使用本轮提供的工具名称，arguments 必须是完整的 JSON 对象。")
            append("不要复述或继续刚才的非法工具调用。")
        }

    }
}
