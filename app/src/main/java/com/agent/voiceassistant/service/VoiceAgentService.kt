package com.agent.voiceassistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.agent.voiceassistant.MainActivity
import com.agent.voiceassistant.MediaButtonReceiver
import com.agent.voiceassistant.agent.LLMConfig
import com.agent.voiceassistant.agent.ReasoningPolicy
import com.agent.voiceassistant.agent.StructuredOutputParser
import com.agent.voiceassistant.agent.buildMainSystemPrompt
import com.agent.voiceassistant.agent.runtime.AgentEvent
import com.agent.voiceassistant.agent.runtime.AgentLoop
import com.agent.voiceassistant.agent.runtime.MainAgentHarness
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import com.agent.voiceassistant.audio.EarconPlayer
import com.agent.voiceassistant.audio.AudioRouteManager
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.SpeechSegmenter
import com.agent.voiceassistant.cloud.SimpleVadRecorder
import com.agent.voiceassistant.cloud.StreamingSpeechExtractor
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.tools.LocalToolExecutor
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import com.agent.voiceassistant.tools.LocationProvider
import com.agent.voiceassistant.tools.MainToolRegistry
import com.agent.voiceassistant.telecom.AssistantTelecomSession
import com.agent.voiceassistant.ui.ChatMessage
import com.agent.voiceassistant.ui.ChatRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.Locale
import java.util.UUID

class VoiceAgentService : Service() {

    companion object {
        private const val CHANNEL_ID = "voice_agent_channel"
        private const val NOTIFICATION_ID = 1
        private const val MAX_HISTORY_MESSAGES = 12
        private const val STREAM_TTS_SAMPLE_RATE = 24_000
        private const val INITIAL_STREAM_BUFFER_BYTES = 9_600
        private const val ENABLE_STREAMING_TTS = true
        private const val ENABLE_PLAYBACK_DONE_EARCON = false
        private const val TTS_OUTPUT_GAIN = 1.0f
        private const val TTS_FADE_MS = 18
        private const val TTS_INTER_SEGMENT_SILENCE_MS = 4
        private const val TTS_FINAL_SILENCE_MS = 90
        private const val FAST_MAX_MODEL_CALLS = 3
        private const val DEEP_MAX_MODEL_CALLS = 4
        private const val FAST_MAX_COMPLETION_TOKENS = 1_024
        private const val DEEP_MAX_COMPLETION_TOKENS = 4_096
        private const val MAX_TOOL_RESULT_CHARS = 12_000

        const val ACTION_START = "com.agent.voiceassistant.START"
        const val ACTION_STOP = "com.agent.voiceassistant.STOP"
        const val ACTION_WAKE = "com.agent.voiceassistant.WAKE"
        const val ACTION_SLEEP = "com.agent.voiceassistant.SLEEP"
        const val ACTION_TOGGLE = "com.agent.voiceassistant.TOGGLE"
        const val ACTION_TEXT_INPUT = "com.agent.voiceassistant.TEXT_INPUT"
        private const val EXTRA_TEXT = "text"

        fun start(ctx: Context) {
            DiagLog.i("api.start", "ctx=${ctx.javaClass.simpleName}")
            val intent = Intent(ctx, VoiceAgentService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            DiagLog.i("api.stop", "ctx=${ctx.javaClass.simpleName}")
            val intent = Intent(ctx, VoiceAgentService::class.java).setAction(ACTION_SLEEP)
            ctx.startService(intent)
        }

        fun wake(ctx: Context) {
            DiagLog.i("api.wake", "ctx=${ctx.javaClass.simpleName}")
            val intent = Intent(ctx, VoiceAgentService::class.java).setAction(ACTION_WAKE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun sleep(ctx: Context) {
            DiagLog.i("api.sleep", "ctx=${ctx.javaClass.simpleName}")
            val intent = Intent(ctx, VoiceAgentService::class.java).setAction(ACTION_SLEEP)
            ctx.startService(intent)
        }

        fun toggle(ctx: Context) {
            DiagLog.i("api.toggle", "ctx=${ctx.javaClass.simpleName}")
            val intent = Intent(ctx, VoiceAgentService::class.java).setAction(ACTION_TOGGLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun sendText(ctx: Context, text: String) {
            val intent = Intent(ctx, VoiceAgentService::class.java)
                .setAction(ACTION_TEXT_INPUT)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    enum class State {
        INITIALIZING,
        READY,
        LISTENING,
        FAILED
    }

    private val _state = MutableStateFlow(State.INITIALIZING)
    val state = _state.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var speechClient: CloudSpeechClient? = null
    private var recorder: SimpleVadRecorder? = null
    private var routeManager: AudioRouteManager? = null
    private var player: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private lateinit var store: ConversationStore
    private lateinit var locationProvider: LocationProvider
    private lateinit var toolRegistry: MainToolRegistry
    private lateinit var executionEnv: AndroidExecutionEnv
    private lateinit var skillRegistry: SkillRegistry
    private val agentHarness = MainAgentHarness()
    private lateinit var earcons: EarconPlayer
    private lateinit var telecomSession: AssistantTelecomSession
    private var dormant = true
    private val turnMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.i("service.create", "pid=${android.os.Process.myPid()}", showInUi = true)
        store = ConversationStore(this)
        locationProvider = LocationProvider(this)
        executionEnv = AndroidExecutionEnv(this)
        skillRegistry = SkillRegistry(executionEnv.skillsRoot)
        toolRegistry = MainToolRegistry(
            LocalToolExecutor(
                store = store,
                locationProvider = locationProvider,
                executionEnv = executionEnv,
            ),
        )
        earcons = EarconPlayer { routeManager }
        telecomSession = AssistantTelecomSession(this)
        telecomSession.register()
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagLog.i(
            "service.start_command",
            "action=${intent?.action} startId=$startId dormant=$dormant loop=${loopJob?.isActive == true}",
            showInUi = true,
        )
        when (intent?.action) {
            ACTION_START, ACTION_WAKE -> wakeAgent()
            ACTION_SLEEP -> sleepAgent()
            ACTION_TOGGLE -> toggleAgent()
            Intent.ACTION_MEDIA_BUTTON -> handleMediaButtonIntent(intent)
            ACTION_TEXT_INPUT -> {
                ensureDormantForeground()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    serviceScope.launch { processUserText(text.trim(), source = "text") }
                }
            }
            ACTION_STOP -> {
                hardStopAgent(keepForeground = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hardStopAgent(keepForeground = false)
        telecomSession.endListening("service_destroy")
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun toggleAgent() {
        DiagLog.i(
            "agent.toggle.begin",
            "dormant=$dormant loop=${loopJob?.isActive == true} recorder=${recorder != null}",
            showInUi = true,
        )
        if (dormant) wakeAgent() else sleepAgent()
        DiagLog.i(
            "agent.toggle.end",
            "dormant=$dormant loop=${loopJob?.isActive == true} recorder=${recorder != null}",
            showInUi = true,
        )
    }

    private fun wakeAgent() {
        DiagLog.i("agent.wake.begin", "dormant=$dormant loop=${loopJob?.isActive == true}", showInUi = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("唤醒中..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("唤醒中..."))
        }
        if (loopJob?.isActive == true && !dormant) return

        val config = LLMConfig.auto()
        if (config.apiKey.isBlank()) {
            DiagLog.w("agent.wake.fail", "missing_api_key", showInUi = true)
            fail("未配置 LLM_API_KEY")
            return
        }

        dormant = false
        updateMediaPlaybackState()
        telecomSession.beginListening()
        val routes = AudioRouteManager(this)
        routeManager = routes
        val routeSummary = routes.configureForVoiceSession()
        if (speechClient == null) {
            speechClient = CloudSpeechClient(config)
        }
        recorder = SimpleVadRecorder(routes)
        _state.value = State.LISTENING
        emitState(ServiceState.LISTENING)
        emitLog("Agent 已唤醒")
        emitLog(routeSummary)
        DiagLog.i("agent.wake.ready", "route=${routeSummary.take(160)}", showInUi = true)
        updateNotification("聆听中...")

        if (loopJob?.isActive != true) {
            loopJob = serviceScope.launch {
                runConversationLoop()
            }
        }
    }

    private fun handleMediaButtonIntent(intent: Intent): Boolean {
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        if (event == null) {
            DiagLog.w("media.service.no_event", showInUi = true)
            return true
        }
        DiagLog.i(
            "media.service.event",
            "key=${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} repeat=${event.repeatCount}",
            showInUi = true,
        )
        if (event.action != KeyEvent.ACTION_DOWN) return true
        Timber.i("Service media button: keyCode=${event.keyCode}")
        return when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                DiagLog.i("media.service.toggle", "key=${KeyEvent.keyCodeToString(event.keyCode)}", showInUi = true)
                toggleAgent()
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                DiagLog.i("media.service.sleep", showInUi = true)
                sleepAgent()
                true
            }
            else -> {
                DiagLog.i("media.service.unhandled", "key=${KeyEvent.keyCodeToString(event.keyCode)}")
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runConversationLoop() {
        while (coroutineContext.isActive && !dormant) {
            try {
                updateNotification("聆听中...")
                emitState(ServiceState.LISTENING)
                emitLog("请说话")
                earcons.listening()
                val recording = recorder?.recordNextUtterance() ?: break
                earcons.captureDone()
                emitLog("录音完成 ${recording.durationMs}ms，停止收音")
                processTurn(recording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Voice loop failed")
                emitLog("本轮失败: ${e.message}")
            }
        }
    }

    private suspend fun processTurn(recording: SimpleVadRecorder.Recording) {
        val client = speechClient ?: return
        updateNotification("识别中...")
        val userText = client.transcribe(recording.wavBytes)
        if (userText.isBlank()) {
            emitLog("ASR 返回为空，重新聆听")
            earcons.error()
            return
        }

        processUserText(userText, source = "voice")
    }

    private suspend fun processUserText(userText: String, source: String) {
        turnMutex.withLock {
            try {
                if (handleLocalConversationCommand(userText)) return
                val client = ensureSpeechClient() ?: return

                store.addMessage("user", userText)
                EventBus.emitChatMessage(ChatMessage(ChatRole.USER, userText))
                emitLog("你($source): $userText")
                updateNotification("正在回应...")

                val outcome = if (ReasoningPolicy.requestsDeepReasoning(userText)) {
                    runDeepReasoningTurn(client, reason = "用户明确要求深入思考")
                } else {
                    val fastOutcome = runAgentLoop(
                        client = client,
                        messages = buildMessages(deepReasoning = false),
                        thinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
                        maxModelCalls = FAST_MAX_MODEL_CALLS,
                        allowReasoningEscalation = true,
                    )
                    when (fastOutcome) {
                        is AgentLoop.Outcome.Completed -> fastOutcome
                        is AgentLoop.Outcome.Escalate -> runDeepReasoningTurn(client, fastOutcome.reason)
                    }
                }
                if (ENABLE_PLAYBACK_DONE_EARCON && outcome.playedSpeech) {
                    earcons.playbackDone()
                }
                updateNotification(if (dormant) "休眠中，等待唤醒" else "聆听中...")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "processUserText failed")
                val message = "本轮失败: ${e.message ?: e.javaClass.simpleName}"
                store.addMessage("system", message)
                EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, message))
                emitLog(message)
                earcons.error()
                updateNotification(if (dormant) "休眠中，等待唤醒" else "聆听中...")
            }
        }
    }

    private suspend fun runDeepReasoningTurn(
        client: CloudSpeechClient,
        reason: String,
    ): AgentLoop.Outcome.Completed = coroutineScope {
        val status = "开启深度思考"
        emitToolStatus(status)
        emitLog("$status: $reason")
        updateNotification("深入思考中...")

        val acknowledgement = toolRegistry.audibleAcknowledgement(
            MainToolRegistry.TOOL_REQUEST_DEEP_REASONING,
        ).orEmpty()
        val acknowledgementJob = async {
            if (acknowledgement.isNotBlank()) {
                speakAssistantText(client, acknowledgement)
            }
        }
        val outcome = runAgentLoop(
            client = client,
            messages = buildMessages(deepReasoning = true),
            thinkingMode = CloudSpeechClient.ThinkingMode.ENABLED,
            maxModelCalls = DEEP_MAX_MODEL_CALLS,
            allowReasoningEscalation = false,
            beforeSpeech = { acknowledgementJob.await() },
        )
        acknowledgementJob.await()
        when (outcome) {
            is AgentLoop.Outcome.Completed -> outcome.copy(
                playedSpeech = outcome.playedSpeech || acknowledgement.isNotBlank(),
            )
            is AgentLoop.Outcome.Escalate -> error("深度思考模式不能再次升级")
        }
    }

    private suspend fun runAgentLoop(
        client: CloudSpeechClient,
        messages: List<CloudSpeechClient.LlmMessage>,
        thinkingMode: CloudSpeechClient.ThinkingMode,
        maxModelCalls: Int,
        allowReasoningEscalation: Boolean,
        beforeSpeech: suspend () -> Unit = {},
    ): AgentLoop.Outcome {
        val loop = AgentLoop(
            runtime = object : AgentLoop.Runtime {
                override fun toolDefinitions(allowReasoningEscalation: Boolean) =
                    toolRegistry.definitions(
                        profile = MainToolRegistry.Profile.STANDALONE,
                        allowReasoningEscalation = allowReasoningEscalation,
                    )

                override suspend fun modelTurn(
                    request: CloudSpeechClient.ChatRequest,
                    beforeSpeech: suspend () -> Unit,
                    onStreamEvent: (CloudSpeechClient.ChatStreamEvent) -> Unit,
                ): AgentLoop.ModelTurn = streamModelTurn(client, request, beforeSpeech, onStreamEvent)

                override fun normalizeAssistant(message: CloudSpeechClient.LlmMessage) =
                    normalizeLegacyMessage(message)

                override fun isReasoningEscalation(call: CloudSpeechClient.ToolCall) =
                    toolRegistry.isReasoningEscalation(call)

                override fun reasoningEscalationReason(call: CloudSpeechClient.ToolCall) =
                    toolRegistry.reasoningEscalationReason(call)

                override fun toolDisplayName(toolName: String) = toolRegistry.displayName(toolName)

                override suspend fun executeTool(call: CloudSpeechClient.ToolCall) =
                    executeToolCall(client, call)

                override fun blockedTool(call: CloudSpeechClient.ToolCall, reason: String) =
                    blockedToolCall(call, reason)

                override suspend fun finishAssistant(
                    message: CloudSpeechClient.LlmMessage,
                    streamedSpeech: Boolean,
                ): Boolean {
                    val finalText = message.content.orEmpty().trim()
                    store.addMessage("assistant", finalText)
                    EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, finalText))
                    emitLog("助手: $finalText")
                    if (!streamedSpeech) {
                        beforeSpeech()
                        speakAssistantText(client, finalText)
                        return true
                    }
                    return false
                }
            },
            eventSink = ::onAgentEvent,
        )
        return agentHarness.run(
            loop,
            AgentLoop.Config(
                messages = messages,
                thinkingMode = thinkingMode,
                maxModelCalls = maxModelCalls,
                allowReasoningEscalation = allowReasoningEscalation,
                maxCompletionTokens = if (thinkingMode == CloudSpeechClient.ThinkingMode.ENABLED) {
                    DEEP_MAX_COMPLETION_TOKENS
                } else {
                    FAST_MAX_COMPLETION_TOKENS
                },
                beforeSpeech = beforeSpeech,
            ),
        )
    }

    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AgentStarted -> DiagLog.i("agent.loop.started", "turn=${event.turnId}")
            is AgentEvent.TurnStarted -> DiagLog.i(
                "agent.turn.started",
                "turn=${event.turnId} thinking=${event.thinkingMode}",
            )
            is AgentEvent.ToolStarted -> DiagLog.i(
                "agent.tool.started",
                "turn=${event.turnId} id=${event.call.id} name=${event.call.name}",
            )
            is AgentEvent.ToolFinished -> DiagLog.i(
                "agent.tool.finished",
                "turn=${event.turnId} id=${event.call.id} blocked=${event.blocked}",
            )
            is AgentEvent.TurnFinished -> DiagLog.i(
                "agent.turn.finished",
                "turn=${event.turnId} chars=${event.finalText.length}",
            )
            is AgentEvent.AgentFailed -> DiagLog.w(
                "agent.loop.failed",
                "turn=${event.turnId} error=${event.error}",
            )
            is AgentEvent.AgentFinished,
            is AgentEvent.ContentDelta,
            is AgentEvent.MessageFinished,
            is AgentEvent.MessageStarted,
            is AgentEvent.ReasoningDelta,
            is AgentEvent.ToolProgress -> Unit
        }
    }

    private suspend fun executeToolCall(
        client: CloudSpeechClient,
        call: CloudSpeechClient.ToolCall,
    ): CloudSpeechClient.LlmMessage = coroutineScope {
        val title = "调用工具：${toolRegistry.displayName(call.name)}"
        emitToolStatus(title)
        emitLog("$title args=${call.arguments.take(300)}")

        val acknowledgement = toolRegistry.audibleAcknowledgement(call.name)
        val acknowledgementJob = acknowledgement?.let { text ->
            async { speakAssistantText(client, text) }
        }
        val execution = toolRegistry.execute(call)
        acknowledgementJob?.await()

        val result = execution.result
        store.addMessage("tool", result.displayText)
        EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, result.displayText))
        emitLog("工具结果: ${result.contextText}")
        CloudSpeechClient.LlmMessage(
            role = "tool",
            content = result.contextText.take(MAX_TOOL_RESULT_CHARS),
            toolCallId = call.id,
        )
    }

    private fun blockedToolCall(
        call: CloudSpeechClient.ToolCall,
        reason: String,
    ): CloudSpeechClient.LlmMessage {
        val status = "已阻止重复工具调用：${toolRegistry.displayName(call.name)}"
        emitToolStatus(status)
        emitLog("$status id=${call.id}")
        return CloudSpeechClient.LlmMessage(
            role = "tool",
            content = reason,
            toolCallId = call.id,
        )
    }

    private fun emitToolStatus(text: String) {
        store.addMessage("tool", text)
        EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, text))
    }

    private fun normalizeLegacyMessage(message: CloudSpeechClient.LlmMessage): CloudSpeechClient.LlmMessage {
        if (message.toolCalls.isNotEmpty()) return message
        val raw = message.content.orEmpty()
        if (!raw.contains("<LOCAL_ACTION>", ignoreCase = true) &&
            !raw.contains("<HUB_ACTION>", ignoreCase = true) &&
            !raw.contains("<REPLY>", ignoreCase = true)
        ) {
            return message
        }
        val parsed = StructuredOutputParser.parse(raw)
        val calls = parsed.actions.mapIndexed { index, action ->
            toolRegistry.normalizeLegacyAction(
                action = action,
                callId = "legacy_${UUID.randomUUID()}_$index",
            )
        }
        emitLog("兼容旧工具协议：${calls.joinToString { it.name }}")
        return message.copy(content = parsed.speakText, toolCalls = calls)
    }

    private suspend fun streamModelTurn(
        client: CloudSpeechClient,
        request: CloudSpeechClient.ChatRequest,
        beforeSpeech: suspend () -> Unit,
        onStreamEvent: (CloudSpeechClient.ChatStreamEvent) -> Unit,
    ): AgentLoop.ModelTurn = coroutineScope {
        val extractor = StreamingSpeechExtractor()
        val segmenter = SpeechSegmenter()
        val ttsQueue = Channel<String>(Channel.UNLIMITED)
        val startedAt = System.currentTimeMillis()
        var firstDeltaLogged = false
        var firstSegmentLogged = false
        var reasoningStarted = false
        var streamedSpeech = false

        Timber.i("Latency LLM request_start thinking=${request.thinkingMode}")
        val playbackJob = launch {
            val playbackSession = StreamingTtsPlaybackSession(client)
            try {
                var speechGateOpened = false
                for (segment in ttsQueue) {
                    if (!speechGateOpened) {
                        beforeSpeech()
                        speechGateOpened = true
                    }
                    streamedSpeech = true
                    emitLog("播报: $segment")
                    if (!playbackSession.playSentence(segment)) {
                        playFullTtsSentence(client, segment)
                    }
                }
            } finally {
                playbackSession.finish()
            }
        }

        try {
            val completion = client.streamChat(request) { event ->
                onStreamEvent(event)
                when (event) {
                    is CloudSpeechClient.ChatStreamEvent.ReasoningDelta -> {
                        if (!reasoningStarted) {
                            reasoningStarted = true
                            Timber.i("Latency reasoning first_delta elapsed=${System.currentTimeMillis() - startedAt}ms")
                        }
                    }
                    is CloudSpeechClient.ChatStreamEvent.ContentDelta -> {
                        if (!firstDeltaLogged) {
                            firstDeltaLogged = true
                            Timber.i("Latency LLM first_content elapsed=${System.currentTimeMillis() - startedAt}ms")
                        }
                        val speechDelta = extractor.feed(event.text)
                        for (segment in segmenter.feed(speechDelta)) {
                            if (!firstSegmentLogged) {
                                firstSegmentLogged = true
                                Timber.i(
                                    "Latency speech first_segment elapsed=${System.currentTimeMillis() - startedAt}ms " +
                                        "chars=${segment.length}",
                                )
                            }
                            ttsQueue.send(segment)
                        }
                    }
                    is CloudSpeechClient.ChatStreamEvent.ToolCallDelta -> Unit
                    is CloudSpeechClient.ChatStreamEvent.Finished -> Unit
                }
            }
            val tail = extractor.finish()
            for (segment in segmenter.feed(tail)) {
                ttsQueue.send(segment)
            }
            segmenter.flush()?.let { segment ->
                ttsQueue.send(segment)
            }
            Timber.i(
                "Latency LLM done elapsed=${System.currentTimeMillis() - startedAt}ms " +
                    "chars=${completion.message.content.orEmpty().length} tools=${completion.message.toolCalls.size}",
            )
            ttsQueue.close()
            playbackJob.join()
            return@coroutineScope AgentLoop.ModelTurn(completion, streamedSpeech)
        } finally {
            ttsQueue.close()
        }
    }

    private fun buildMessages(deepReasoning: Boolean): List<CloudSpeechClient.LlmMessage> = buildList {
        val system = buildString {
            append(buildMainSystemPrompt(deepReasoning))
            append("\n\n当前时间：")
            append(
                ZonedDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE XXX", Locale.CHINA),
                ),
            )
            append("\n\n本地上下文：\n")
            append(store.contextSummary())
            append("\n\nAgent 虚拟文件系统：\n")
            append(executionEnv.virtualRootSummary())
            append("\n\nSkill 索引：\n")
            append(skillRegistry.promptSummary())
            append("\n\n可用凭据 profile（仅可引用名称，认证值不会进入上下文）：\n")
            append(executionEnv.credentialProfileSummary())
        }
        add(CloudSpeechClient.LlmMessage("system", system))
        addAll(store.llmHistory(MAX_HISTORY_MESSAGES))
    }

    private suspend fun speakAssistantText(client: CloudSpeechClient, text: String) {
        val sentenceBuffer = SpeechSegmenter()
        val sentences = buildList {
            addAll(sentenceBuffer.feed(text))
            sentenceBuffer.flush()?.let { add(it) }
        }
        val playbackSession = StreamingTtsPlaybackSession(client)
        var streamedAny = false
        try {
            for (sentence in sentences) {
                emitLog("播报: $sentence")
                if (playbackSession.playSentence(sentence)) {
                    streamedAny = true
                } else {
                    playFullTtsSentence(client, sentence)
                }
            }
        } finally {
            playbackSession.finish()
        }
    }

    private suspend fun handleLocalConversationCommand(text: String): Boolean {
        val normalized = text.trim().lowercase()
        val compact = normalized.replace(" ", "")
        val isNew = compact == "/new" ||
            compact in setOf("开启新话题", "新建会话", "新开话题", "重新开始一个话题", "重新开始")
        if (!isNew) return false

        store.startNewConversation(reason = text)
        EventBus.emitChatReset(emptyList())
        val msg = "已开启新话题"
        store.addMessage("system", msg)
        EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, msg))
        emitLog(msg)
        serviceScope.launch {
            val location = locationProvider.currentLocation(timeoutMs = 8_000L, forceFresh = true)
            if (location != null) {
                store.setLocation(location)
                val locationMsg = "新话题定位已更新"
                store.addMessage("tool", locationMsg)
                EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, locationMsg))
            }
        }
        return true
    }

    private fun ensureSpeechClient(): CloudSpeechClient? {
        speechClient?.let { return it }
        val config = LLMConfig.auto()
        if (config.apiKey.isBlank()) {
            fail("未配置 LLM_API_KEY")
            return null
        }
        return CloudSpeechClient(config).also { speechClient = it }
    }

    private suspend fun playFullTtsSentence(client: CloudSpeechClient, sentence: String) {
        if (sentence.isBlank()) return
        if (ENABLE_STREAMING_TTS) {
            Timber.i("TTS full fallback for chars=${sentence.length}")
        }

        val startedAt = System.currentTimeMillis()
        val audio = runCatching { client.synthesizeSpeech(sentence) }
            .onSuccess {
                Timber.i(
                    "TTS full response bytes=${it.bytes.size} mime=${it.mimeType} " +
                        "elapsed=${System.currentTimeMillis() - startedAt}ms",
                )
            }
            .onFailure { Timber.e(it, "TTS failed for: $sentence") }
            .getOrNull()
        if (audio != null) {
            playAudio(audio)
        }
    }

    private inner class StreamingTtsPlaybackSession(
        private val client: CloudSpeechClient,
    ) {
        private var audioTrack: AudioTrack? = null
        private var bytesWritten = 0
        private var sampleRate = STREAM_TTS_SAMPLE_RATE
        private var firstAudioLogged = false
        private var playbackStartedLogged = false
        private val focus = requestPlaybackFocus()
        private val startedAt = System.currentTimeMillis()

        suspend fun playSentence(sentence: String): Boolean = withContext(Dispatchers.IO) {
            if (!ENABLE_STREAMING_TTS || sentence.isBlank()) return@withContext false

            var pendingTail = ByteArray(0)
            var wroteAudio = false
            var sentenceSampleRate = sampleRate
            val result = runCatching {
                Timber.i("Latency TTS stream_start chars=${sentence.length}")
                val streamed = client.streamSynthesizeSpeech(sentence) { payload ->
                    val rawChunk = decodePcmChunk(payload.bytes)
                    if (rawChunk.pcm.isEmpty()) return@streamSynthesizeSpeech
                    sentenceSampleRate = rawChunk.sampleRate
                    val chunk = rawChunk.copy(pcm = amplifyPcm16Le(rawChunk.pcm))
                    if (!firstAudioLogged) {
                        firstAudioLogged = true
                        Timber.i(
                            "Latency TTS first_audio elapsed=${System.currentTimeMillis() - startedAt}ms " +
                                "bytes=${payload.bytes.size} mime=${payload.mimeType}",
                        )
                    }
                    val track = ensureTrack(chunk.sampleRate, payload.mimeType, rawChunk.pcm, chunk.pcm)
                    val pcm = chunk.pcm
                    if (!wroteAudio) {
                        fadeInPcm16LeInPlace(pcm, chunk.sampleRate)
                    }
                    wroteAudio = true

                    val combined = concatBytes(pendingTail, pcm)
                    val tailBytes = fadeByteCount(chunk.sampleRate).coerceAtMost(combined.size)
                    val writableBytes = (combined.size - tailBytes).coerceAtLeast(0)
                    if (writableBytes > 0) {
                        writePcm(track, combined, 0, writableBytes)
                    }
                    pendingTail = if (tailBytes > 0) {
                        combined.copyOfRange(writableBytes, combined.size)
                    } else {
                        ByteArray(0)
                    }
                }
                val track = audioTrack
                if (track != null && pendingTail.isNotEmpty()) {
                    fadeOutPcm16LeInPlace(pendingTail, sentenceSampleRate)
                    writePcm(track, pendingTail, 0, pendingTail.size)
                    bytesWritten += writeSilence(track, sentenceSampleRate, TTS_INTER_SEGMENT_SILENCE_MS)
                    pendingTail = ByteArray(0)
                }
                streamed && wroteAudio && audioTrack != null
            }
            if (result.isFailure) {
                Timber.w(result.exceptionOrNull(), "Streaming TTS failed, fallback to full audio")
                finish()
                false
            } else {
                result.getOrDefault(false)
            }
        }

        suspend fun finish() = withContext(Dispatchers.IO) {
            val track = audioTrack
            if (track != null && bytesWritten > 0) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                    logPlaybackStarted()
                }
                bytesWritten += writeSilenceTail(track, sampleRate)
                waitForAudioTrack(track, bytesWritten, sampleRate)
                Timber.i("Latency TTS stream_done elapsed=${System.currentTimeMillis() - startedAt}ms bytes=$bytesWritten")
            }
            audioTrack?.let { releaseAudioTrack(it) }
            audioTrack = null
            abandonPlaybackFocus(focus)
        }

        private fun ensureTrack(
            chunkSampleRate: Int,
            mimeType: String?,
            rawPcm: ByteArray,
            boostedPcm: ByteArray,
        ): AudioTrack {
            val existing = audioTrack
            if (existing != null && chunkSampleRate == sampleRate) return existing

            if (existing != null) {
                releaseAudioTrack(existing)
                bytesWritten = 0
            }

            sampleRate = chunkSampleRate
            val track = createPcmTrack(sampleRate)
            runCatching { track.setVolume(1f) }
            routeManager?.applyOutputRouting(track)
            audioTrack = track
            Timber.i(
                "TTS stream sampleRate=$sampleRate mime=$mimeType " +
                    "pcm ${describePcm(rawPcm)} -> ${describePcm(boostedPcm)} gain=$TTS_OUTPUT_GAIN",
            )
            return track
        }

        private fun writePcm(track: AudioTrack, pcm: ByteArray, offset: Int, size: Int) {
            var writtenOffset = offset
            val end = offset + size
            while (writtenOffset < end) {
                val written = track.write(pcm, writtenOffset, end - writtenOffset)
                if (written <= 0) break
                writtenOffset += written
                bytesWritten += written
                startPlaybackIfReady(track)
            }
        }

        private fun startPlaybackIfReady(track: AudioTrack) {
            if (bytesWritten >= INITIAL_STREAM_BUFFER_BYTES &&
                track.playState != AudioTrack.PLAYSTATE_PLAYING
            ) {
                track.play()
                logPlaybackStarted()
            }
        }

        private fun logPlaybackStarted() {
            if (!playbackStartedLogged) {
                playbackStartedLogged = true
                Timber.i(
                    "Latency audio playback_start elapsed=${System.currentTimeMillis() - startedAt}ms " +
                        "bufferedBytes=$bytesWritten",
                )
            }
        }
    }

    private data class PcmChunk(val pcm: ByteArray, val sampleRate: Int)
    private data class WavDataChunk(
        val offset: Int,
        val size: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
    )

    private fun decodePcmChunk(bytes: ByteArray): PcmChunk {
        if (!bytes.startsWith("RIFF")) return PcmChunk(bytes, STREAM_TTS_SAMPLE_RATE)
        val data = findWavDataChunk(bytes) ?: return PcmChunk(ByteArray(0), STREAM_TTS_SAMPLE_RATE)
        return if (data.bitsPerSample == 16 && data.size > 0) {
            PcmChunk(bytes.copyOfRange(data.offset, data.offset + data.size), data.sampleRate)
        } else {
            Timber.w("Unsupported TTS wav chunk: bits=${data.bitsPerSample} sampleRate=${data.sampleRate}")
            PcmChunk(ByteArray(0), data.sampleRate)
        }
    }

    private fun findWavDataChunk(bytes: ByteArray): WavDataChunk? {
        if (bytes.size < 44 || !bytes.startsWith("RIFF")) return null
        val sampleRate = readLeInt(bytes, 24)
        val bitsPerSample = readLeShort(bytes, 34)
        var offset = 12
        while (offset <= bytes.size - 8) {
            val chunkSize = readLeInt(bytes, offset + 4)
            if (chunkSize < 0) return null
            val dataOffset = offset + 8
            val boundedSize = min(chunkSize, bytes.size - dataOffset)
            if (bytes[offset] == 'd'.code.toByte() &&
                bytes[offset + 1] == 'a'.code.toByte() &&
                bytes[offset + 2] == 't'.code.toByte() &&
                bytes[offset + 3] == 'a'.code.toByte()
            ) {
                return WavDataChunk(dataOffset, boundedSize, sampleRate, bitsPerSample)
            }
            offset = dataOffset + chunkSize + (chunkSize and 1)
        }
        return null
    }

    private fun createPcmTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(INITIAL_STREAM_BUFFER_BYTES * 2)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(playbackAudioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer,
                AudioTrack.MODE_STREAM,
            )
        }
    }

    private suspend fun waitForAudioTrack(track: AudioTrack, bytesWritten: Int, sampleRate: Int) {
        val totalFrames = bytesWritten / 2
        val timeoutMs = (totalFrames * 1000L / sampleRate) + 2_000L
        val startedAt = System.currentTimeMillis()
        while (track.playbackHeadPosition < totalFrames &&
            System.currentTimeMillis() - startedAt < timeoutMs
        ) {
            delay(20)
        }
    }

    private fun writeSilenceTail(track: AudioTrack, sampleRate: Int): Int {
        return writeSilence(track, sampleRate, TTS_FINAL_SILENCE_MS)
    }

    private fun writeSilence(track: AudioTrack, sampleRate: Int, durationMs: Int): Int {
        val tailBytes = ByteArray(sampleRate * 2 * durationMs / 1000)
        var offset = 0
        while (offset < tailBytes.size) {
            val written = track.write(tailBytes, offset, tailBytes.size - offset)
            if (written <= 0) break
            offset += written
        }
        return offset
    }

    private fun concatBytes(first: ByteArray, second: ByteArray): ByteArray {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val out = ByteArray(first.size + second.size)
        System.arraycopy(first, 0, out, 0, first.size)
        System.arraycopy(second, 0, out, first.size, second.size)
        return out
    }

    private fun fadeInPcm16LeInPlace(bytes: ByteArray, sampleRate: Int) {
        applyFadePcm16LeInPlace(bytes, sampleRate, fadeIn = true)
    }

    private fun fadeOutPcm16LeInPlace(bytes: ByteArray, sampleRate: Int) {
        applyFadePcm16LeInPlace(bytes, sampleRate, fadeIn = false)
    }

    private fun applyFadePcm16LeInPlace(bytes: ByteArray, sampleRate: Int, fadeIn: Boolean) {
        val fadeBytes = fadeByteCount(sampleRate).coerceAtMost(bytes.size)
        val samples = fadeBytes / 2
        if (samples <= 1) return
        for (sampleIndex in 0 until samples) {
            val byteIndex = sampleIndex * 2
            val sample = (((bytes[byteIndex + 1].toInt() shl 8) or (bytes[byteIndex].toInt() and 0xFF)))
                .toShort()
                .toInt()
            val denominator = (samples - 1).toDouble()
            val factor = if (fadeIn) {
                sampleIndex / denominator
            } else {
                (samples - 1 - sampleIndex) / denominator
            }
            val faded = (sample * factor)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[byteIndex] = (faded and 0xFF).toByte()
            bytes[byteIndex + 1] = ((faded shr 8) and 0xFF).toByte()
        }
    }

    private fun fadeByteCount(sampleRate: Int): Int {
        val bytes = sampleRate * TTS_FADE_MS * 2 / 1000
        return bytes - (bytes % 2)
    }

    private fun releaseAudioTrack(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    private suspend fun playAudio(audio: CloudSpeechClient.AudioPayload) {
        if (audio.bytes.isEmpty()) return
        val boostedBytes = boostEncodedAudioIfPossible(audio.bytes)
        val file = withContext(Dispatchers.IO) {
            val ext = audioExtension(audio)
            File(cacheDir, "tts-${System.nanoTime()}.$ext").apply {
                writeBytes(boostedBytes)
            }
        }

        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { cont ->
                val mediaPlayer = MediaPlayer()
                player = mediaPlayer
                val focus = requestPlaybackFocus()
                mediaPlayer.setAudioAttributes(playbackAudioAttributes())
                mediaPlayer.setVolume(1f, 1f)
                routeManager?.applyOutputRouting(mediaPlayer)
                mediaPlayer.setOnPreparedListener { it.start() }
                mediaPlayer.setOnCompletionListener {
                    releasePlayer(it)
                    abandonPlaybackFocus(focus)
                    file.delete()
                    if (cont.isActive) cont.resume(Unit)
                }
                mediaPlayer.setOnErrorListener { mp, what, extra ->
                    releasePlayer(mp)
                    abandonPlaybackFocus(focus)
                    file.delete()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("MediaPlayer error $what/$extra"))
                    true
                }
                cont.invokeOnCancellation {
                    releasePlayer(mediaPlayer)
                    abandonPlaybackFocus(focus)
                    file.delete()
                }
                runCatching {
                    mediaPlayer.setDataSource(file.absolutePath)
                    mediaPlayer.prepareAsync()
                }.onFailure {
                    releasePlayer(mediaPlayer)
                    abandonPlaybackFocus(focus)
                    file.delete()
                    if (cont.isActive) cont.resumeWithException(it)
                }
            }
        }
    }

    private fun releasePlayer(mp: MediaPlayer) {
        runCatching { mp.setOnCompletionListener(null) }
        runCatching { mp.setOnErrorListener(null) }
        runCatching { mp.stop() }
        runCatching { mp.release() }
        if (player === mp) player = null
    }

    private fun audioExtension(audio: CloudSpeechClient.AudioPayload): String {
        val mime = audio.mimeType.orEmpty().lowercase()
        return when {
            "wav" in mime || audio.bytes.startsWith("RIFF") -> "wav"
            "mpeg" in mime || "mp3" in mime || audio.bytes.startsWith("ID3") -> "mp3"
            "ogg" in mime || audio.bytes.startsWith("OggS") -> "ogg"
            "flac" in mime || audio.bytes.startsWith("fLaC") -> "flac"
            else -> "mp3"
        }
    }

    private fun ByteArray.startsWith(prefix: String): Boolean {
        val bytes = prefix.toByteArray(Charsets.US_ASCII)
        if (size < bytes.size) return false
        for (i in bytes.indices) if (this[i] != bytes[i]) return false
        return true
    }

    private fun playbackAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private fun boostEncodedAudioIfPossible(bytes: ByteArray): ByteArray {
        val data = findWavDataChunk(bytes)
        if (data == null || data.bitsPerSample != 16 || data.size <= 0) return bytes
        val boosted = bytes.copyOf()
        amplifyPcm16LeInPlace(boosted, data.offset, data.offset + data.size)
        Timber.i(
            "TTS wav sampleRate=${data.sampleRate} pcm " +
                "${describePcm(bytes, data.offset, data.offset + data.size)} -> " +
                "${describePcm(boosted, data.offset, data.offset + data.size)} gain=$TTS_OUTPUT_GAIN",
        )
        return boosted
    }

    private fun amplifyPcm16Le(bytes: ByteArray): ByteArray {
        val boosted = bytes.copyOf()
        amplifyPcm16LeInPlace(boosted, 0, boosted.size)
        return boosted
    }

    private fun amplifyPcm16LeInPlace(bytes: ByteArray, start: Int, endExclusive: Int) {
        var i = start
        val end = endExclusive - 1
        while (i < end) {
            val sample = (((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF))).toShort().toInt()
            val boosted = (sample * TTS_OUTPUT_GAIN)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i] = (boosted and 0xFF).toByte()
            bytes[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun describePcm(bytes: ByteArray, start: Int = 0, endExclusive: Int = bytes.size): String {
        var i = start
        val end = endExclusive - 1
        var count = 0
        var peak = 0
        var sumSquares = 0.0
        while (i < end) {
            val sample = (((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF))).toShort().toInt()
            peak = maxOf(peak, abs(sample))
            sumSquares += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return "empty"
        val rms = sqrt(sumSquares / count) / Short.MAX_VALUE
        val peakRatio = peak.toDouble() / Short.MAX_VALUE
        return "rms=${"%.3f".format(rms)} peak=${"%.3f".format(peakRatio)} samples=$count"
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
    }

    private fun readLeShort(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun sleepAgent(keepForeground: Boolean = true) {
        DiagLog.i(
            "agent.sleep.begin",
            "keepForeground=$keepForeground dormant=$dormant loop=${loopJob?.isActive == true} recorder=${recorder != null}",
            showInUi = true,
        )
        if (dormant && loopJob == null && recorder == null) {
            if (keepForeground) ensureDormantForeground()
            DiagLog.i("agent.sleep.noop", "already_dormant", showInUi = true)
            return
        }
        dormant = true
        loopJob?.cancel()
        loopJob = null
        recorder?.stop()
        recorder = null
        player?.let { releasePlayer(it) }
        player = null
        _state.value = State.READY
        emitState(ServiceState.DORMANT)
        emitLog("Agent 已休眠")
        updateMediaPlaybackState()
        if (keepForeground) ensureDormantForeground()
        DiagLog.i(
            "agent.sleep.done",
            "dormant=$dormant loop=${loopJob?.isActive == true} recorder=${recorder != null}",
            showInUi = true,
        )
        val routesToRelease = routeManager
        serviceScope.launch {
            runCatching { earcons.sleep() }
            routesToRelease?.release()
            if (routeManager === routesToRelease) {
                routeManager = null
            }
            telecomSession.endListening("agent_sleep")
        }
    }

    private fun ensureDormantForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("休眠中，等待唤醒"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("休眠中，等待唤醒"))
        }
    }

    private fun hardStopAgent(keepForeground: Boolean = false) {
        sleepAgent(keepForeground = keepForeground)
        val client = speechClient
        speechClient = null
        if (client != null) {
            thread(name = "cloud-speech-shutdown", isDaemon = true) {
                runCatching { client.shutdown() }
                    .onFailure { Timber.w(it, "CloudSpeechClient shutdown failed") }
            }
        }
    }

    private fun fail(message: String) {
        _state.value = State.FAILED
        emitState(ServiceState.FAILED)
        emitLog(message)
        updateNotification("启动失败")
    }

    private fun emitLog(msg: String) {
        Timber.i("LogBus: $msg")
        EventBus.emitLog(msg)
    }

    private fun emitState(state: ServiceState) {
        EventBus.emitState(state)
    }

    private fun setupMediaSession() {
        val mediaButtonReceiver = ComponentName(this, MediaButtonReceiver::class.java)
        DiagLog.i("media.session.setup", "receiver=${mediaButtonReceiver.flattenToShortString()}", showInUi = true)
        val mediaButtonIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(mediaButtonReceiver),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val session = MediaSessionCompat(this, "VoiceAgentSession", mediaButtonReceiver, mediaButtonIntent).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Timber.i("MediaSession onPlay -> toggle")
                    DiagLog.i("media.session.on_play", showInUi = true)
                    toggleAgent()
                }

                override fun onPause() {
                    Timber.i("MediaSession onPause -> toggle")
                    DiagLog.i("media.session.on_pause", showInUi = true)
                    toggleAgent()
                }

                override fun onStop() {
                    Timber.i("MediaSession onStop -> sleep")
                    DiagLog.i("media.session.on_stop", showInUi = true)
                    sleepAgent()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    DiagLog.i("media.session.on_media_button", "action=${mediaButtonEvent.action}", showInUi = true)
                    return handleMediaButtonIntent(mediaButtonEvent)
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    DiagLog.i("media.session.on_custom", "action=$action", showInUi = true)
                    when (action) {
                        ACTION_WAKE -> wakeAgent()
                        ACTION_SLEEP -> sleepAgent()
                    }
                }
            })
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            isActive = true
        }
        mediaSession = session
        updateMediaPlaybackState()
        DiagLog.i("media.session.ready", "active=${session.isActive}", showInUi = true)
    }

    private fun updateMediaPlaybackState() {
        val state = if (dormant) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        DiagLog.i("media.session.state", "state=${if (dormant) "PAUSED" else "PLAYING"}")
    }

    private fun requestPlaybackFocus(): AudioFocusRequest? {
        val mgr = getSystemService(AudioManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAudioAttributes())
                .build()
            mgr.requestAudioFocus(request)
            request
        } else {
            @Suppress("DEPRECATION")
            mgr.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            null
        }
    }

    private fun abandonPlaybackFocus(request: AudioFocusRequest?) {
        val mgr = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            mgr.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            mgr.abandonAudioFocus(null)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音助手",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "语音助手运行中"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val controlIntent = Intent(this, VoiceAgentService::class.java).setAction(
            if (dormant) ACTION_WAKE else ACTION_SLEEP
        )
        val controlPi = PendingIntent.getService(
            this,
            1,
            controlIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val actionIcon = if (dormant) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val actionText = if (dormant) "唤醒" else "休眠"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(actionIcon, actionText, controlPi)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

}
