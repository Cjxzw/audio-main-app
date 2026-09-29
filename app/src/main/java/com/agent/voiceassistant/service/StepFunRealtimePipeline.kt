package com.agent.voiceassistant.service

import com.agent.voiceassistant.agent.buildMainSystemPrompt
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
    private val queryMainConversation: (String, Int) -> String,
    private val delegateToMain: suspend (String) -> String,
    private val onSessionFinished: (String) -> Unit,
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
    private var responseDone = false
    private var pendingToolCount = 0
    private var awaitingToolFollowUp = false
    private var toolFollowUpRetryCount = 0
    private var toolFollowUpRetryJob: Job? = null
    @Volatile private var stopping = false
    private val sessionGeneration = AtomicLong(0L)
    private val sessionBoundaryLock = Any()
    private var acceptingVoiceTranscripts = false
    private val disconnectReported = AtomicBoolean(false)
    private val sessionFinishedNotified = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
        client.connect(apiKey, config)
        client.updateSession(config, instructions(), realtimeToolDefinitions())
        eventsJob = scope.launch {
            client.events.collect(::handleEvent)
        }
        if (captureAudio) startAudioCapture()
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
        EventBus.emitChatMessage(ChatMessage(ChatRole.USER, normalized, stored.timestamp, stored.id))
        try {
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
            toolStatusMessageIds.values.toList().also { toolStatusMessageIds.clear() }
        }
        staleToolStatusIds.forEach { messageId ->
            store.deleteMessage(messageId)
            EventBus.emitChatRemoval(messageId)
        }
        if (sessionFinishedNotified.compareAndSet(false, true)) {
            val transcript = store.conversationProjection(conversationId, recentTurns = 50)
            if (transcript.isNotBlank()) onSessionFinished(transcript)
        }
        acceptingVoiceTranscripts = false
        recorder.stop()
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

    private suspend fun handleEvent(event: StepFunRealtimeProtocol.Event) {
        when (event) {
            StepFunRealtimeProtocol.Event.SessionCreated,
            StepFunRealtimeProtocol.Event.SessionUpdated,
            is StepFunRealtimeProtocol.Event.Unknown -> Unit
            StepFunRealtimeProtocol.Event.UserSpeechStarted -> {
                interruptAssistantForBargeIn()
                beginUserTranscriptTurn()
            }
            StepFunRealtimeProtocol.Event.UserSpeechStopped -> {
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
            is StepFunRealtimeProtocol.Event.TextDelta -> appendAssistantText(event.itemId, event.text, TextSource.TEXT)
            is StepFunRealtimeProtocol.Event.AudioTranscriptDelta -> appendAssistantText(event.itemId, event.text, TextSource.AUDIO_TRANSCRIPT)
            is StepFunRealtimeProtocol.Event.ThinkingDelta -> appendThinking(event.itemId, event.text)
            is StepFunRealtimeProtocol.Event.AudioDelta -> {
                activeAssistantItemId = event.itemId ?: activeAssistantItemId
                runCatching { player.enqueue(Base64.getDecoder().decode(event.base64Audio)) }
                    .onFailure { error -> onLog("Realtime 音频入队失败：${error.message ?: error.javaClass.simpleName}") }
            }
            StepFunRealtimeProtocol.Event.AudioDone -> player.finishResponseAudio()
            is StepFunRealtimeProtocol.Event.FunctionArgumentsDone -> executeTool(event, sessionGeneration.get())
            StepFunRealtimeProtocol.Event.ResponseCreated -> {
                toolFollowUpRetryJob?.cancel()
                toolFollowUpRetryJob = null
            }
            is StepFunRealtimeProtocol.Event.ResponseDone -> {
                finishAssistantResponse()
                responseDone = true
                awaitingToolFollowUp = false
                flushToolOutputsWhenPlaybackDrains()
            }
            is StepFunRealtimeProtocol.Event.Error -> {
                if (awaitingToolFollowUp && event.message.contains("ongoing response already exists", ignoreCase = true)) {
                    scheduleToolFollowUpRetry()
                } else if (isStarted) {
                    onLog(event.message)
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
        if (call.name == MainToolRegistry.TOOL_AGENT_SLEEP) {
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
                    if (!isSessionCurrent(generation)) return@runCatching result
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
                if (!isSessionCurrent(generation)) return@launch
                finishToolStatus(call, output.success)
                pendingToolOutputs += output.callId to output.content
                pendingToolCount -= 1
            }
            flushToolOutputsWhenPlaybackDrains()
        }
    }

    private fun finishToolStatus(call: CloudSpeechClient.ToolCall, success: Boolean) {
        val state = if (success) ToolDisplayStatus.SUCCEEDED else ToolDisplayStatus.FAILED
        val messageId = toolStatusMessageIds.remove(call.id) ?: return
        val text = tools.displaySummary(call) ?: tools.displayName(call.name)
        store.updateMessageInConversation(conversationId, messageId, text, toolStatus = state)
        EventBus.emitChatMessage(
            ChatMessage(ChatRole.SYSTEM, text, messageId = messageId, toolCallId = call.id, toolStatus = state),
        )
    }

    private fun flushToolOutputsWhenPlaybackDrains() {
        if (!responseDone || pendingToolCount != 0) return
        val generation = sessionGeneration.get()
        scope.launch {
            // Keep a short guard after response.done so tool follow-up cannot overlap spoken filler.
            delay(150)
            if (!isSessionCurrent(generation)) return@launch
            val outputs = synchronized(sessionBoundaryLock) {
                if (!isSessionCurrent(generation)) return@launch
                val copy = pendingToolOutputs.toList()
                pendingToolOutputs.clear()
                copy
            }
            if (outputs.isEmpty()) return@launch
            responseDone = false
            awaitingToolFollowUp = true
            toolFollowUpRetryCount = 0
            runCatching {
                if (!isSessionCurrent(generation)) return@runCatching
                outputs.forEach { (callId, content) -> client.sendFunctionOutput(callId, content) }
                if (!isSessionCurrent(generation)) return@runCatching
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

    fun injectMainAssistantReply(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank() || !isStarted) return
        runCatching { client.sendContext("主会话后台进展（仅供当前通话了解，不需要复述这条上下文）：$normalized") }
            .onFailure { onLog("主会话进展回灌失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun realtimeToolDefinitions(): List<CloudSpeechClient.ToolDefinition> = tools.definitions(
        profile = MainToolRegistry.Profile.REALTIME,
        allowReasoningEscalation = false,
    )

    private fun defaultInstructions(): String = buildString {
        append(buildMainSystemPrompt())
        append("\n\n当前是全双工实时语音会话。回复应简洁自然；只能使用查询主会话、查询记忆和转交主会话工具。不要暴露内部思考。")
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
