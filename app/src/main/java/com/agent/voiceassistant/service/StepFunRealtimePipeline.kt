package com.agent.voiceassistant.service

import com.agent.voiceassistant.agent.buildRealtimeSystemPrompt
import com.agent.voiceassistant.audio.AudioRouteManager
import com.agent.voiceassistant.audio.RealtimeAudioRecorder
import com.agent.voiceassistant.audio.RealtimePcmPlayer
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.realtime.RealtimePlainTextPolicy
import com.agent.voiceassistant.cloud.realtime.RealtimeTranscriptAccumulator
import com.agent.voiceassistant.cloud.realtime.StepFunRealtimeClient
import com.agent.voiceassistant.cloud.realtime.StepFunRealtimeProtocol
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.settings.RealtimePipelineRepository
import com.agent.voiceassistant.tools.MainToolRegistry
import com.agent.voiceassistant.ui.ChatMessage
import com.agent.voiceassistant.ui.ChatRole
import com.agent.voiceassistant.ui.ChatStreamState
import com.agent.voiceassistant.ui.ReasoningDisplayItem
import com.agent.voiceassistant.ui.ReasoningItemKind
import com.agent.voiceassistant.ui.ToolDisplayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * The StepFun realtime path intentionally does not use AgentLoop. It owns a persistent WebSocket
 * session, maps all user-visible deltas into ConversationStore, and delegates only tool execution
 * to the existing local registry.
 */
class StepFunRealtimePipeline(
    private val scope: CoroutineScope,
    private val store: ConversationStore,
    private val routes: AudioRouteManager,
    private val settings: RealtimePipelineRepository,
    private val tools: MainToolRegistry,
    private val conversationId: String,
    private val mainSnapshot: () -> String,
    private val queryMainConversation: (String, Int) -> String,
    private val delegateToMain: suspend (String) -> String,
    private val onUndeliveredMainReply: (String, String, Boolean) -> Unit,
    private val onSessionFinished: () -> Unit,
    private val instructions: () -> String,
    private val onLog: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onSleepRequested: () -> Unit,
) {
    private val client = StepFunRealtimeClient()
    private val player = RealtimePcmPlayer(routes, scope)
    private val recorder = RealtimeAudioRecorder(
        routeManager = routes,
        onPcmFrame = client::appendAudio,
        onVolume = EventBus::emitVolume,
    )
    private val toolStatusMessageIds = ConcurrentHashMap<String, String>()
    private val pendingToolOutputs = mutableListOf<Pair<String, String>>()
    private var eventsJob: Job? = null
    private var captureJob: Job? = null
    private var assistantDraft: AssistantDraft? = null
    private var userDraft: UserDraft? = null
    private var nextUserTurnId = 0L
    private var transcriptFallbackJob: Job? = null
    private var activeAssistantItemId: String? = null
    @Volatile private var responseDone = false
    @Volatile private var pendingToolCount = 0
    @Volatile private var awaitingToolFollowUp = false
    private var toolFollowUpRetryCount = 0
    private var toolFollowUpRetryJob: Job? = null
    @Volatile private var stopping = false
    private val sessionGeneration = AtomicLong(0L)
    private val sessionBoundaryLock = Any()
    private var acceptingVoiceTranscripts = false
    @Volatile private var warmingUp = true
    private var warmupReady: CompletableDeferred<Unit>? = null
    private var warmupResponseDone: CompletableDeferred<Unit>? = null
    private var warmupStopped: CompletableDeferred<Unit>? = null
    private val disconnectReported = AtomicBoolean(false)
    private val sessionFinishedNotified = AtomicBoolean(false)
    @Volatile private var userSpeaking = false
    @Volatile private var responseInFlight = false
    @Volatile private var awaitingUserResponse = false
    private val pendingMainReplies = linkedMapOf<String, Pair<String, Boolean>>()
    private val reportedMainTurns = linkedSetOf<String>()
    private var mainReplyJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val isReady: Boolean
        get() = isStarted && !warmingUp && !stopping && warmupReady == null

    val isStarted: Boolean
        get() = client.state.value == StepFunRealtimeClient.ConnectionState.CONNECTED

    suspend fun start(captureAudio: Boolean = true) {
        check(eventsJob == null && captureJob == null) { "Realtime 管线已经启动" }
        sessionGeneration.incrementAndGet()
        stopping = false
        val config = settings.stepFunConfig()
        val apiKey = settings.stepFunApiKey()
        disconnectReported.set(false)
        sessionFinishedNotified.set(false)
        onLog("Realtime 正在连接，开始预热")
        client.connect(apiKey, config)
        eventsJob = scope.launch {
            client.events.collect(::handleEvent)
        }
        client.updateSession(config, instructions(), realtimeToolDefinitions())
        warmingUp = true
        warmupReady = CompletableDeferred()
        warmupResponseDone = CompletableDeferred()
        warmupStopped = CompletableDeferred()
        client.sendText("[系统预热] 连接初始化检查。只返回 READY，不要调用工具，不要输出其他内容。")
        runCatching {
            withTimeout(15_000L) {
                kotlinx.coroutines.selects.select<Unit> {
                    warmupResponseDone!!.onAwait { }
                    warmupStopped!!.onAwait { throw kotlinx.coroutines.CancellationException("Realtime 预热已停止") }
                }
            }
        }.onFailure {
            warmingUp = false
            warmupReady = null
            warmupResponseDone = null
            warmupStopped = null
            if (it is kotlinx.coroutines.CancellationException) throw it
            throw IllegalStateException("Realtime 预热失败：${it.message ?: it.javaClass.simpleName}", it)
        }
        warmupReady = null
        warmupResponseDone = null
        warmupStopped = null
        val snapshot = mainSnapshot()
        client.sendContext("这是通话开始时的主会话快照，可能已过期；需要更早或更细的内容请调用 main_conversation_query。\n" +
            snapshot.ifBlank { "最近没有可注入的完整轮次。" })
        warmingUp = false
        if (captureAudio) startAudioCapture()
        flushMainReplies()
        onLog(
            if (captureAudio) "StepFun Realtime 已连接，持续聆听中" else "StepFun Realtime 已连接，等待文字输入或唤醒",
        )
    }

    fun startAudioCapture() {
        if (captureJob?.isActive == true) return
        check(isStarted) { "Realtime 会话尚未就绪" }
        acceptingVoiceTranscripts = true
        captureJob = scope.launch {
            runCatching { recorder.capture() }
                .onFailure { error ->
                    acceptingVoiceTranscripts = false
                    notifyDisconnected("Realtime 麦克风已停止：${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    fun submitText(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        val stored = store.addMessageToConversation(conversationId, "user", normalized)
        EventBus.emitChatMessage(ChatMessage(ChatRole.USER, normalized, stored.timestamp, stored.id, conversationId = conversationId))
        try {
            responseInFlight = true
            client.sendText(normalized)
        } catch (error: Throwable) {
            store.deleteMessage(stored.id)
            EventBus.emitChatRemoval(stored.id)
            throw error
        }
    }

    fun stop() {
        val staleToolStatusIds = synchronized(sessionBoundaryLock) {
            stopping = true
            sessionGeneration.incrementAndGet()
            pendingToolOutputs.clear()
            pendingToolCount = 0
            awaitingToolFollowUp = false
            responseDone = false
            pendingMainReplies.forEach { (id, result) -> onUndeliveredMainReply(id, result.first, result.second) }
            pendingMainReplies.clear()
            toolStatusMessageIds.values.toList().also { toolStatusMessageIds.clear() }
        }
        staleToolStatusIds.forEach { messageId ->
            store.deleteMessage(messageId)
            EventBus.emitChatRemoval(messageId)
        }
        warmupStopped?.complete(Unit)
        if (sessionFinishedNotified.compareAndSet(false, true)) {
            userDraft?.takeIf { !it.completed }?.let {
                if (it.text.isBlank()) it.text.append(it.candidateTranscript.orEmpty())
                persistUserDraft(it, ChatStreamState.INTERRUPTED)
                it.completed = true
            }
            assistantDraft?.let { persistAssistantDraft(it, ChatStreamState.INTERRUPTED) }
            onSessionFinished()
        }
        acceptingVoiceTranscripts = false
        recorder.stop()
        mainReplyJob?.cancel()
        mainReplyJob = null
        captureJob?.cancel()
        captureJob = null
        toolFollowUpRetryJob?.cancel()
        toolFollowUpRetryJob = null
        transcriptFallbackJob?.cancel()
        transcriptFallbackJob = null
        eventsJob?.cancel()
        eventsJob = null
        player.release()
        client.shutdown()
        EventBus.emitVolume(0f)
    }

    private fun handleEvent(event: StepFunRealtimeProtocol.Event) = synchronized(sessionBoundaryLock) {
        if (stopping) return@synchronized
        when (event) {
            StepFunRealtimeProtocol.Event.SessionCreated,
            StepFunRealtimeProtocol.Event.SessionUpdated,
            is StepFunRealtimeProtocol.Event.Unknown -> Unit
            StepFunRealtimeProtocol.Event.UserSpeechStarted -> {
                synchronized(sessionBoundaryLock) { userSpeaking = true; responseInFlight = true; awaitingUserResponse = true }
                interruptAssistantForBargeIn()
                beginUserTranscriptTurn()
            }
            StepFunRealtimeProtocol.Event.UserSpeechStopped -> {
                userSpeaking = false
                markUserSpeechStopped()
                onLog("Realtime 检测到用户语音结束")
            }
            is StepFunRealtimeProtocol.Event.UserTranscriptDelta -> if (acceptingVoiceTranscripts) {
                appendUserTranscript(event.itemId, event.text)
            }
            is StepFunRealtimeProtocol.Event.UserTranscriptCandidate -> if (acceptingVoiceTranscripts) {
                recordUserTranscriptCandidate(event.itemId, event.text)
            }
            is StepFunRealtimeProtocol.Event.UserTranscriptDone -> if (acceptingVoiceTranscripts) {
                finishUserTranscript(event.itemId, event.text)
            }
            is StepFunRealtimeProtocol.Event.TextDelta -> {
                if (warmingUp) {
                    if (event.text.contains("READY", ignoreCase = true)) warmupReady?.complete(Unit)
                } else {
                    appendAssistantText(event.itemId, event.text, TextSource.TEXT)
                }
            }
            is StepFunRealtimeProtocol.Event.AudioTranscriptDelta -> if (!warmingUp) appendAssistantText(event.itemId, event.text, TextSource.AUDIO_TRANSCRIPT)
            is StepFunRealtimeProtocol.Event.ThinkingDelta -> if (!warmingUp) appendThinking(event.itemId, event.text)
            is StepFunRealtimeProtocol.Event.AudioDelta -> {
                if (warmingUp) return@synchronized
                activeAssistantItemId = event.itemId ?: activeAssistantItemId
                runCatching { player.enqueue(Base64.getDecoder().decode(event.base64Audio)) }
                    .onFailure { error -> onLog("Realtime 音频入队失败：${error.message ?: error.javaClass.simpleName}") }
            }
            StepFunRealtimeProtocol.Event.AudioDone -> {
                if (!warmingUp && !stopping) player.finishResponseAudio()
            }
            is StepFunRealtimeProtocol.Event.FunctionArgumentsDone -> executeTool(event, sessionGeneration.get())
            StepFunRealtimeProtocol.Event.ResponseCreated -> {
                awaitingUserResponse = false
                responseInFlight = true
                toolFollowUpRetryJob?.cancel()
                toolFollowUpRetryJob = null
            }
            is StepFunRealtimeProtocol.Event.ResponseDone -> {
                if (warmingUp) {
                    responseInFlight = false
                    // response.done is authoritative: Realtime may return the warmup reply
                    // on audio_transcript/audio tracks instead of response.text.delta.
                    warmupReady?.complete(Unit)
                    warmupResponseDone?.complete(Unit)
                    return@synchronized
                }
                finishAssistantResponse()
                responseDone = true
                responseInFlight = false
                awaitingToolFollowUp = false
                flushToolOutputsWhenPlaybackDrains()
                flushMainReplies()
            }
            is StepFunRealtimeProtocol.Event.Error -> {
                if (awaitingToolFollowUp && event.message.contains("ongoing response already exists", ignoreCase = true)) {
                    scheduleToolFollowUpRetry()
                } else if (isStarted) {
                    responseInFlight = false
                    awaitingUserResponse = false
                    onLog(event.message)
                    flushMainReplies()
                } else {
                    notifyDisconnected(event.message)
                }
            }
        }
    }

    private fun notifyDisconnected(reason: String) {
        if (stopping || !disconnectReported.compareAndSet(false, true)) return
        acceptingVoiceTranscripts = false
        recorder.stop()
        player.release()
        onDisconnected(reason)
    }

    private fun beginUserTranscriptTurn() {
        if (userDraft?.completed == false) return
        transcriptFallbackJob?.cancel()
        transcriptFallbackJob = null
        userDraft?.takeIf { !it.completed && it.text.isNotBlank() }?.let { interrupted ->
            persistUserDraft(interrupted, ChatStreamState.INTERRUPTED)
        }
        userDraft = UserDraft(turnId = ++nextUserTurnId)
    }

    private fun markUserSpeechStopped() {
        val draft = userDraft ?: return
        if (draft.completed) return
        draft.awaitingFinal = true
        scheduleTranscriptFallback(draft)
    }

    private fun appendUserTranscript(itemId: String?, delta: String) {
        if (delta.isBlank()) return
        val draft = activeUserDraft() ?: return
        if (draft.completed) return
        itemId?.let(draft.serverItemIds::add)
        val merged = RealtimeTranscriptAccumulator.merge(draft.text.toString(), delta)
        if (merged == draft.text.toString()) return
        draft.text.setLength(0)
        draft.text.append(merged)
        persistUserDraft(draft, ChatStreamState.STREAMING)
        DiagLog.i("realtime.asr.delta", "turn=${draft.turnId} item=${itemId ?: "none"} chars=${merged.length}")
    }

    private fun recordUserTranscriptCandidate(itemId: String?, transcript: String) {
        if (transcript.isBlank()) return
        val draft = activeUserDraft() ?: return
        if (draft.completed) return
        itemId?.let(draft.serverItemIds::add)
        draft.candidateTranscript = transcript
        scheduleTranscriptFallback(draft)
        DiagLog.i("realtime.asr.candidate", "turn=${draft.turnId} item=${itemId ?: "none"} chars=${transcript.length}")
    }

    private fun finishUserTranscript(itemId: String?, transcript: String) {
        val draft = activeUserDraft() ?: return
        if (draft.completed) return
        itemId?.let(draft.serverItemIds::add)
        val finalText = transcript.ifBlank { draft.candidateTranscript.orEmpty() }
        if (finalText.isBlank()) return
        transcriptFallbackJob?.cancel()
        transcriptFallbackJob = null
        draft.text.setLength(0)
        draft.text.append(finalText)
        draft.completed = true
        persistUserDraft(draft, ChatStreamState.COMPLETED)
        DiagLog.i("realtime.asr.final", "turn=${draft.turnId} item=${itemId ?: "none"} chars=${finalText.length}")
    }

    private fun activeUserDraft(): UserDraft? {
        val current = userDraft
        if (current != null) return current
        // Server VAD normally emits speech_started first; this protects partial compatibility.
        return UserDraft(turnId = ++nextUserTurnId).also { userDraft = it }
    }

    private fun scheduleTranscriptFallback(draft: UserDraft) {
        if (!draft.awaitingFinal || draft.candidateTranscript.isNullOrBlank() || transcriptFallbackJob?.isActive == true) return
        transcriptFallbackJob = scope.launch {
            delay(FINAL_TRANSCRIPT_FALLBACK_MS)
            if (userDraft !== draft || draft.completed) return@launch
            val candidate = draft.candidateTranscript.orEmpty()
            if (candidate.isBlank()) return@launch
            draft.text.setLength(0)
            draft.text.append(candidate)
            draft.completed = true
            persistUserDraft(draft, ChatStreamState.COMPLETED)
            DiagLog.w("realtime.asr.fallback", "turn=${draft.turnId} chars=${candidate.length}")
        }
    }

    private fun persistUserDraft(draft: UserDraft, state: ChatStreamState) {
        val text = draft.text.toString().trim()
        if (text.isBlank()) return
        if (draft.messageId == null) {
            val stored = store.addMessageToConversation(conversationId, "user", text, streamState = state)
            draft.messageId = stored.id
            draft.timestamp = stored.timestamp
        } else {
            store.updateMessageInConversation(conversationId, requireNotNull(draft.messageId), text, streamState = state)
        }
        EventBus.emitChatMessage(
            ChatMessage(
                role = ChatRole.USER,
                text = text,
                timestamp = draft.timestamp,
                messageId = draft.messageId,
                streamState = state,
                conversationId = conversationId,
            ),
        )
    }

    private fun appendAssistantText(itemId: String?, delta: String, source: TextSource) {
        if (delta.isBlank()) return
        val draft = assistantDraft ?: AssistantDraft().also { assistantDraft = it }
        if (draft.textSource != null && draft.textSource != source) return
        draft.textSource = source
        draft.text.append(delta)
        activeAssistantItemId = itemId ?: activeAssistantItemId
        persistAssistantDraft(draft, ChatStreamState.STREAMING)
    }

    private fun appendThinking(itemId: String?, delta: String) {
        if (delta.isBlank()) return
        val draft = assistantDraft ?: AssistantDraft().also { assistantDraft = it }
        draft.reasoning.append(delta)
        activeAssistantItemId = itemId ?: activeAssistantItemId
        persistAssistantDraft(draft, ChatStreamState.STREAMING)
    }

    private fun persistAssistantDraft(draft: AssistantDraft, state: ChatStreamState) {
        val text = RealtimePlainTextPolicy.normalize(draft.text.toString())
        val reasoning = draft.reasoning.toString().trim()
        val items = reasoning.takeIf(String::isNotBlank)
            ?.let { listOf(ReasoningDisplayItem(ReasoningItemKind.MARKDOWN, it)) }
            .orEmpty()
        if (draft.messageId == null) {
            val stored = store.addMessageToConversation(
                conversationId = conversationId,
                role = "assistant",
                content = text,
                streamState = state,
                reasoningItems = items,
                llmVisible = state != ChatStreamState.STREAMING,
            )
            draft.messageId = stored.id
            draft.timestamp = stored.timestamp
        } else {
            store.updateMessageInConversation(
                conversationId = conversationId,
                messageId = requireNotNull(draft.messageId),
                content = text,
                streamState = state,
                reasoningItems = items,
                llmVisible = state != ChatStreamState.STREAMING,
            )
        }
        EventBus.emitChatMessage(
            ChatMessage(
                role = ChatRole.BOT,
                text = text,
                timestamp = draft.timestamp,
                messageId = draft.messageId,
                streamState = state,
                reasoningItems = items,
                modelId = settings.stepFunConfig().modelId,
                conversationId = conversationId,
            ),
        )
    }

    private fun finishAssistantResponse() {
        assistantDraft?.let { draft -> persistAssistantDraft(draft, ChatStreamState.COMPLETED) }
        assistantDraft = null
        activeAssistantItemId = null
    }

    private fun interruptAssistantForBargeIn() {
        val itemId = activeAssistantItemId
        if (itemId == null && assistantDraft == null) return
        runCatching { client.cancelResponse() }
        itemId?.let { active -> runCatching { client.truncateAssistantAudio(active, player.playedAudioMs()) } }
        player.interrupt()
        assistantDraft?.let { draft -> persistAssistantDraft(draft, ChatStreamState.INTERRUPTED) }
        assistantDraft = null
        activeAssistantItemId = null
        onLog("用户已打断当前回复")
    }

    private fun executeTool(event: StepFunRealtimeProtocol.Event.FunctionArgumentsDone, generation: Long) {
        if (!isSessionCurrent(generation)) return
        if (event.callId.isBlank() || event.name.isBlank()) {
            onLog("Realtime 工具调用缺少标识")
            return
        }
        val call = CloudSpeechClient.ToolCall(event.callId, event.name, event.arguments)
        DiagLog.i("realtime.tool.call", "name=${call.name} callId=${call.id} argsChars=${call.arguments.length}")
        if (call.name == MainToolRegistry.TOOL_AGENT_SLEEP || call.name == MainToolRegistry.TOOL_REALTIME_HANGUP) {
            onSleepRequested()
            return
        }
        synchronized(sessionBoundaryLock) {
            if (!isSessionCurrent(generation)) return
            val status = store.addMessageToConversation(
                conversationId = conversationId,
                role = "system",
                content = tools.displaySummary(call) ?: tools.displayName(call.name),
                toolCallId = call.id,
                toolStatus = ToolDisplayStatus.RUNNING,
                llmVisible = false,
            )
            toolStatusMessageIds[call.id] = status.id
            EventBus.emitChatMessage(
                ChatMessage(
                    role = ChatRole.SYSTEM,
                    text = status.content,
                    timestamp = status.timestamp,
                    messageId = status.id,
                    toolCallId = call.id,
                    toolStatus = ToolDisplayStatus.RUNNING,
                    conversationId = conversationId,
                ),
            )
            pendingToolCount += 1
        }
        scope.launch {
            val output = runCatching {
                val result = when (call.name) {
                    MainToolRegistry.TOOL_MAIN_CONVERSATION_QUERY -> {
                        val payload = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrDefault(kotlinx.serialization.json.JsonObject(emptyMap()))
                        val keyword = (payload["keyword"] as? JsonPrimitive)?.content.orEmpty()
                        val turns = (payload["recent_turns"] as? JsonPrimitive)?.intOrNull ?: 6
                        ToolOutput(call.id, queryMainConversation(keyword, turns), true)
                    }
                    MainToolRegistry.TOOL_DELEGATE_TO_MAIN -> {
                        val payload = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrDefault(kotlinx.serialization.json.JsonObject(emptyMap()))
                        val content = (payload["content"] as? JsonPrimitive)?.content.orEmpty()
                        ToolOutput(call.id, delegateToMain(content), true)
                    }
                    else -> {
                        val execution = tools.execute(call)
                        ToolOutput(call.id, execution.result.contextText, execution.result.success)
                    }
                }
                synchronized(sessionBoundaryLock) {
                    if (!isSessionCurrent(generation)) { return@runCatching result }
                    store.addToolResultToConversation(
                        conversationId = conversationId,
                        turnId = "realtime-${UUID.randomUUID()}",
                        call = call,
                        result = CloudSpeechClient.LlmMessage(role = "tool", content = result.content, toolCallId = call.id),
                        success = result.success,
                    )
                }
                result
            }.getOrElse { error ->
                ToolOutput(call.id, "工具执行失败：${error.message ?: error.javaClass.simpleName}", false)
            }
            synchronized(sessionBoundaryLock) {
                if (!isSessionCurrent(generation)) { return@launch }
                finishToolStatus(call, output.success)
                pendingToolOutputs += output.callId to output.content
                pendingToolCount -= 1
            }
            DiagLog.i("realtime.tool.result", "name=${call.name} callId=${call.id} success=${output.success} chars=${output.content.length}")
            flushToolOutputsWhenPlaybackDrains()
        }
    }

    private fun finishToolStatus(call: CloudSpeechClient.ToolCall, success: Boolean) {
        val state = if (success) ToolDisplayStatus.SUCCEEDED else ToolDisplayStatus.FAILED
        val messageId = toolStatusMessageIds.remove(call.id) ?: return
        val text = tools.displaySummary(call) ?: tools.displayName(call.name)
        store.updateMessageInConversation(conversationId, messageId, text, toolStatus = state)
        EventBus.emitChatMessage(
            ChatMessage(ChatRole.SYSTEM, text, messageId = messageId, toolCallId = call.id, toolStatus = state, conversationId = conversationId),
        )
    }

    private fun flushToolOutputsWhenPlaybackDrains() {
        if (!responseDone || pendingToolCount != 0) return
        val generation = sessionGeneration.get()
        scope.launch {
            // Keep a short guard after response.done so tool follow-up cannot overlap spoken filler.
            delay(150)
            player.awaitPlaybackDrained()
            if (!isSessionCurrent(generation)) { return@launch }
            val outputs = synchronized(sessionBoundaryLock) {
                if (!isSessionCurrent(generation)) { return@launch }
                val copy = pendingToolOutputs.toList()
                pendingToolOutputs.clear()
                copy
            }
            if (outputs.isEmpty()) return@launch
            responseDone = false
            awaitingToolFollowUp = true
            toolFollowUpRetryCount = 0
            runCatching {
                if (!isSessionCurrent(generation)) { return@runCatching }
                outputs.forEach { (callId, content) -> client.sendFunctionOutput(callId, content) }
                if (!isSessionCurrent(generation)) { return@runCatching }
                client.createResponse()
            }.onFailure { error ->
                notifyDisconnected("工具结果发送失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun isSessionCurrent(generation: Long): Boolean =
        !stopping && sessionGeneration.get() == generation

    private fun scheduleToolFollowUpRetry() {
        if (!awaitingToolFollowUp || toolFollowUpRetryJob?.isActive == true) return
        if (toolFollowUpRetryCount >= MAX_TOOL_FOLLOW_UP_RETRIES) {
            onLog("Realtime 工具结果已提交，但服务端未能开始后续回复")
            awaitingToolFollowUp = false
            return
        }
        toolFollowUpRetryCount += 1
        toolFollowUpRetryJob = scope.launch {
            delay(TOOL_FOLLOW_UP_RETRY_DELAY_MS * toolFollowUpRetryCount)
            runCatching { client.createResponse() }
                .onFailure { error -> notifyDisconnected("工具后续回复重试失败：${error.message ?: error.javaClass.simpleName}") }
        }
    }

    fun injectMainAssistantReply(turnId: String, text: String, failed: Boolean = false): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank() || stopping) return false
        synchronized(sessionBoundaryLock) {
            if (stopping) return false
            if (turnId in reportedMainTurns || turnId in pendingMainReplies) return true
            pendingMainReplies[turnId] = normalized to failed
        }
        flushMainReplies()
        return true
    }

    private fun canReportMainReply(): Boolean = synchronized(sessionBoundaryLock) {
        RealtimeReportPolicy.canReport(isReady, userSpeaking, responseInFlight || awaitingUserResponse,
            pendingToolCount, awaitingToolFollowUp || pendingToolOutputs.isNotEmpty())
    }

    private fun flushMainReplies() = synchronized(sessionBoundaryLock) {
        if (stopping || mainReplyJob?.isActive == true || pendingMainReplies.isEmpty()) return@synchronized
        val generation = sessionGeneration.get()
        mainReplyJob = scope.launch {
            try {
                while (isSessionCurrent(generation)) {
                    delay(200)
                    if (!canReportMainReply()) continue
                    player.awaitPlaybackDrained()
                    synchronized(sessionBoundaryLock) {
                        if (!isSessionCurrent(generation) || !canReportMainReply()) return@synchronized
                        val entry = pendingMainReplies.entries.firstOrNull() ?: return@launch
                        val (text, failed) = entry.value
                        try {
                            client.sendContext(if (failed) "主会话任务最终失败，请用自然中文向用户报告：$text" else
                                "主会话任务已完成，请向用户汇报以下最终正文：$text")
                            responseInFlight = true
                            client.createResponse()
                            pendingMainReplies.remove(entry.key)
                            reportedMainTurns += entry.key
                            if (reportedMainTurns.size > 128) reportedMainTurns.remove(reportedMainTurns.first())
                        } catch (error: Exception) {
                            responseInFlight = false
                            onLog("主会话结果回灌失败：${error.message ?: error.javaClass.simpleName}")
                            return@launch
                        }
                    }
                }
            } finally {
                synchronized(sessionBoundaryLock) { mainReplyJob = null }
            }
        }
    }

    private fun realtimeToolDefinitions(): List<CloudSpeechClient.ToolDefinition> = tools.definitions(
        profile = MainToolRegistry.Profile.REALTIME,
        allowReasoningEscalation = false,
    )

    private fun defaultInstructions(): String = buildString {
        append(buildRealtimeSystemPrompt())
    }

    private data class UserDraft(
        val turnId: Long,
        val text: StringBuilder = StringBuilder(),
        val serverItemIds: MutableSet<String> = linkedSetOf(),
        var candidateTranscript: String? = null,
        var awaitingFinal: Boolean = false,
        var completed: Boolean = false,
        var messageId: String? = null,
        var timestamp: Long = System.currentTimeMillis(),
    )

    private data class AssistantDraft(
        val text: StringBuilder = StringBuilder(),
        val reasoning: StringBuilder = StringBuilder(),
        var textSource: TextSource? = null,
        var messageId: String? = null,
        var timestamp: Long = System.currentTimeMillis(),
    )

    private data class ToolOutput(val callId: String, val content: String, val success: Boolean)

    private enum class TextSource { TEXT, AUDIO_TRANSCRIPT }

    private companion object {
        const val TOOL_FOLLOW_UP_RETRY_DELAY_MS = 750L
        const val MAX_TOOL_FOLLOW_UP_RETRIES = 3
        const val FINAL_TRANSCRIPT_FALLBACK_MS = 1_500L
    }
}
