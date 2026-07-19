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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.agent.voiceassistant.MainActivity
import com.agent.voiceassistant.MediaButtonReceiver
import com.agent.voiceassistant.R
import com.agent.voiceassistant.agent.LLMConfig
import com.agent.voiceassistant.agent.StructuredOutputParser
import com.agent.voiceassistant.agent.SpokenReplyPolicy
import com.agent.voiceassistant.agent.buildCurrentTurnUserContent
import com.agent.voiceassistant.agent.buildMainSystemPrompt
import com.agent.voiceassistant.agent.deepReasoningEnabledResult
import com.agent.voiceassistant.agent.runtime.AgentEvent
import com.agent.voiceassistant.agent.runtime.AgentLoop
import com.agent.voiceassistant.agent.runtime.MainAgentHarness
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import com.agent.voiceassistant.audio.EarconPlayer
import com.agent.voiceassistant.audio.AudioRouteManager
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.NetworkTimeoutException
import com.agent.voiceassistant.cloud.SpeechSegmenter
import com.agent.voiceassistant.cloud.SimpleVadRecorder
import com.agent.voiceassistant.cloud.StreamingSpeechExtractor
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.media.MainMediaLibraryService
import com.agent.voiceassistant.tools.LocalToolExecutor
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import com.agent.voiceassistant.tools.CodeGraphIndex
import com.agent.voiceassistant.tools.LocationProvider
import com.agent.voiceassistant.tools.MainToolRegistry
import com.agent.voiceassistant.telecom.AssistantTelecomSession
import com.agent.voiceassistant.ui.ChatMessage
import com.agent.voiceassistant.ui.ChatRole
import com.agent.voiceassistant.ui.ToolDisplayStatus
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
import java.io.ByteArrayOutputStream
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class VoiceAgentService : Service() {

    companion object {
        private const val CHANNEL_ID = "voice_agent_channel"
        private const val NOTIFICATION_ID = 1
        private const val STREAM_TTS_SAMPLE_RATE = 24_000
        private const val INITIAL_STREAM_BUFFER_BYTES = 9_600
        private const val ENABLE_STREAMING_TTS = true
        private const val ENABLE_LEGACY_MEDIA_SESSION = false
        private const val ENABLE_PLAYBACK_DONE_EARCON = false
        private const val TTS_OUTPUT_GAIN = 1.2f
        private const val TTS_FADE_MS = 18
        private const val TTS_FINAL_SILENCE_MS = 90
        private const val DEEP_MAX_TOOL_ROUNDS = 10
        private const val FAST_MAX_COMPLETION_TOKENS = 1_024
        private const val DEEP_MAX_COMPLETION_TOKENS = 4_096
        private const val MAX_TOOL_RESULT_CHARS = 12_000
        private const val SESSION_GREETING_MESSAGE_ID = "__session_greeting__"
        private const val SESSION_GREETING_TRIGGER =
            "这是 Main Agent 的内部会话事件：用户刚刚开启了一个新话题。请只向用户发送一句简短、自然的中文问候，并邀请用户提出新的话题。不要调用工具，不要提及这个内部事件。"
        private val THINKING_FEEDBACK_AUDIO = intArrayOf(
            R.raw.thinking_um_long,
            R.raw.thinking_er_long,
            R.raw.thinking_zhege,
            R.raw.thinking_wo_xiang_yixia,
            R.raw.thinking_wo_xiangxiang,
            R.raw.thinking_lvilv,
            R.raw.thinking_xianrang,
            R.raw.thinking_shaodeng,
            R.raw.thinking_zuzhi,
            R.raw.thinking_liaojie,
        )
        private val TECHNICAL_SPEECH_HINTS = listOf(
            "代码",
            "源码",
            "日志",
            "排查",
            "诊断",
            "报错",
            "故障",
            "方案",
            "分析",
            "修复",
            "debug",
            "stack trace",
        )

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
    private val toolStatusMessageIds = ConcurrentHashMap<String, String>()
    private val thinkingFeedbackLock = Any()
    private var lastThinkingFeedbackAudio: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.i("service.create", "pid=${android.os.Process.myPid()}", showInUi = true)
        store = ConversationStore(this)
        locationProvider = LocationProvider(this, store)
        executionEnv = AndroidExecutionEnv(this)
        skillRegistry = SkillRegistry(executionEnv.skillsRoot)
        toolRegistry = MainToolRegistry(
            LocalToolExecutor(
                store = store,
                locationProvider = locationProvider,
                executionEnv = executionEnv,
                codeGraph = CodeGraphIndex(this),
            ),
        )
        locationProvider.refreshInBackground("service_start")
        earcons = EarconPlayer { routeManager }
        telecomSession = AssistantTelecomSession(this)
        telecomSession.register()
        createNotificationChannel()
        MainMediaLibraryService.ensureStarted(this)
        if (ENABLE_LEGACY_MEDIA_SESSION) {
            setupMediaSession()
        }
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
                ensureForegroundForCurrentState()
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
        locationProvider.close()
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
        ensureForeground("唤醒中...", microphoneActive = true)
        if (loopJob?.isActive == true && !dormant) return

        val config = LLMConfig.auto()
        if (config.apiKey.isBlank()) {
            DiagLog.w("agent.wake.fail", "missing_api_key", showInUi = true)
            fail("未配置 LLM_API_KEY")
            return
        }

        dormant = false
        updateMediaPlaybackState()
        MainMediaLibraryService.publishState(this, active = true, status = "聆听中")
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
            } catch (e: NetworkTimeoutException) {
                handleConnectionLost("voice", e)
                continue
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

        val turnNote = if (recording.truncated) {
            "这段语音已达到 60 秒安全上限，转写内容可能不完整。不要自行补全被截断的语义；必要时请用户继续说。"
        } else {
            null
        }
        processUserText(userText, source = "voice", turnNote = turnNote)
    }

    private suspend fun processUserText(
        userText: String,
        source: String,
        turnNote: String? = null,
    ) {
        turnMutex.withLock {
            try {
                if (handleLocalConversationCommand(userText)) return
                val client = ensureSpeechClient() ?: return

                val currentUserMessage = store.addMessage("user", userText)
                EventBus.emitChatMessage(ChatMessage(ChatRole.USER, userText))
                turnNote?.let {
                    store.addMessage("system", it)
                    EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, it))
                }
                emitLog("你($source): $userText")
                updateNotification("正在回应...")

                val outcome = runAgentLoop(
                    client = client,
                    messages = buildMessages(
                        userText = userText,
                        currentUserMessageId = currentUserMessage.id,
                        source = source,
                        turnNote = turnNote,
                    ),
                    initialThinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
                    maxToolRounds = DEEP_MAX_TOOL_ROUNDS,
                    allowReasoningEscalation = true,
                )
                if (ENABLE_PLAYBACK_DONE_EARCON && outcome.playedSpeech) {
                    earcons.playbackDone()
                }
                updateNotification(if (dormant) "休眠中，等待唤醒" else "聆听中...")
            } catch (e: CancellationException) {
                throw e
            } catch (e: NetworkTimeoutException) {
                handleConnectionLost("agent", e)
            } catch (e: Exception) {
                Timber.e(e, "processUserText failed")
                val message = "本轮失败: ${e.message ?: e.javaClass.simpleName}"
                store.addMessage("system", message)
                EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, message))
                emitLog(message)
                val fallback = "这轮没有完成，执行过程中出现了异常。错误已经记入日志，你可以让我重试。"
                store.addMessage("assistant", fallback)
                EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, fallback))
                emitLog("助手兜底: $fallback")
                val client = speechClient
                if (client != null) {
                    try {
                        speakAssistantText(client, fallback)
                    } catch (speechError: Exception) {
                        Timber.w(speechError, "Failed to speak fallback")
                        earcons.error()
                    }
                } else {
                    earcons.error()
                }
                updateNotification(if (dormant) "休眠中，等待唤醒" else "聆听中...")
            }
        }
    }

    private suspend fun runAgentLoop(
        client: CloudSpeechClient,
        messages: List<CloudSpeechClient.LlmMessage>,
        initialThinkingMode: CloudSpeechClient.ThinkingMode,
        maxToolRounds: Int,
        allowReasoningEscalation: Boolean,
        toolsEnabled: Boolean = true,
        beforeSpeech: suspend () -> Unit = {},
    ): AgentLoop.Outcome.Completed {
        var reasoningFeedbackJob: Job? = null

        fun startReasoningFeedback(source: String) {
            if (reasoningFeedbackJob?.isActive == true) return
            reasoningFeedbackJob = serviceScope.launch {
                val startedAt = System.currentTimeMillis()
                DiagLog.i("agent.wait_feedback.started", "source=$source")
                runCatching { playThinkingFeedback() }
                    .onFailure { Timber.w(it, "Reasoning feedback playback failed source=$source") }
                DiagLog.i(
                    "agent.wait_feedback.finished",
                    "source=$source elapsedMs=${System.currentTimeMillis() - startedAt}",
                )
            }
        }

        suspend fun awaitReasoningFeedback() {
            reasoningFeedbackJob?.join()
            reasoningFeedbackJob = null
        }

        val loop = AgentLoop(
            runtime = object : AgentLoop.Runtime {
                override fun toolDefinitions(allowReasoningEscalation: Boolean) =
                    if (toolsEnabled) {
                        toolRegistry.definitions(
                            profile = MainToolRegistry.Profile.STANDALONE,
                            allowReasoningEscalation = allowReasoningEscalation,
                        )
                    } else {
                        emptyList()
                    }

                override suspend fun modelTurn(
                    request: CloudSpeechClient.ChatRequest,
                    beforeSpeech: suspend () -> Unit,
                    onStreamEvent: (CloudSpeechClient.ChatStreamEvent) -> Unit,
                ): AgentLoop.ModelTurn = streamModelTurn(
                    client = client,
                    request = request,
                    beforeSpeech = {
                        awaitReasoningFeedback()
                        beforeSpeech()
                    },
                    onStreamEvent = onStreamEvent,
                )

                override fun normalizeAssistant(message: CloudSpeechClient.LlmMessage) =
                    normalizeLegacyMessage(message)

                override fun isReasoningEscalation(call: CloudSpeechClient.ToolCall) =
                    toolRegistry.isReasoningEscalation(call)

                override fun reasoningEscalationReason(call: CloudSpeechClient.ToolCall) =
                    toolRegistry.reasoningEscalationReason(call)

                override fun onReasoningEscalation(reason: String) {
                    startReasoningFeedback("model")
                }

                override fun reasoningEscalationResult(
                    call: CloudSpeechClient.ToolCall,
                ): AgentLoop.ToolExecution {
                    val reason = toolRegistry.reasoningEscalationReason(call)
                    emitLog("开启深度思考: $reason")
                    updateNotification("深入思考中...")
                    return AgentLoop.ToolExecution(
                        message = CloudSpeechClient.LlmMessage(
                            role = "tool",
                            content = deepReasoningEnabledResult(),
                            toolCallId = call.id,
                        ),
                        succeeded = true,
                    )
                }

                override fun countsTowardAutomaticReasoning(call: CloudSpeechClient.ToolCall): Boolean =
                    toolRegistry.countsTowardAutomaticReasoning(call)

                override suspend fun onAutomaticReasoningEscalation(
                    toolCallCount: Int,
                    triggerCalls: List<CloudSpeechClient.ToolCall>,
                ) {
                    startReasoningFeedback("automatic:$toolCallCount")
                }

                override fun canExecuteToolInParallel(call: CloudSpeechClient.ToolCall): Boolean =
                    toolRegistry.canExecuteInParallel(call)

                override fun toolDisplayName(toolName: String) = toolRegistry.displayName(toolName)

                override suspend fun executeTool(call: CloudSpeechClient.ToolCall) =
                    executeToolCall(call)

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
                        awaitReasoningFeedback()
                        beforeSpeech()
                        speakAssistantText(client, optimizeSpokenReply(finalText))
                        return true
                    }
                    return false
                }
            },
            eventSink = ::onAgentEvent,
        )
        return try {
            when (val outcome = agentHarness.run(
                loop,
                AgentLoop.Config(
                    messages = messages,
                    initialThinkingMode = initialThinkingMode,
                    maxToolRounds = maxToolRounds,
                    allowReasoningEscalation = allowReasoningEscalation,
                    fastMaxCompletionTokens = FAST_MAX_COMPLETION_TOKENS,
                    deepMaxCompletionTokens = DEEP_MAX_COMPLETION_TOKENS,
                    beforeSpeech = beforeSpeech,
                ),
            )) {
                is AgentLoop.Outcome.Completed -> outcome
            }
        } finally {
            awaitReasoningFeedback()
        }
    }

    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AgentStarted -> DiagLog.i("agent.loop.started", "turn=${event.turnId}")
            is AgentEvent.TurnStarted -> DiagLog.i(
                "agent.turn.started",
                "turn=${event.turnId} thinking=${event.thinkingMode}",
            )
            is AgentEvent.ThinkingModeChanged -> DiagLog.i(
                "agent.thinking.changed",
                "turn=${event.turnId} thinking=${event.thinkingMode}",
            )
            is AgentEvent.AutomaticThinkingEscalated -> {
                val callNames = event.triggerCalls.joinToString(",") { it.name }
                DiagLog.i(
                    "agent.thinking.auto_escalated",
                    "turn=${event.turnId} count=${event.toolCallCount} calls=$callNames",
                )
                val status = "正在深入分析"
                store.addMessage("system", status)
                EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, status))
                updateNotification("深入分析中...")
            }
            is AgentEvent.ToolStarted -> {
                DiagLog.i(
                    "agent.tool.started",
                    "turn=${event.turnId} id=${event.call.id} name=${event.call.name}",
                )
                startToolStatus(event.call, event.displayName)
            }
            is AgentEvent.ParallelToolsStarted -> DiagLog.i(
                "agent.tools.parallel",
                "turn=${event.turnId} count=${event.calls.size} names=${event.calls.joinToString(",") { it.name }}",
            )
            is AgentEvent.ToolFinished -> {
                DiagLog.i(
                    "agent.tool.finished",
                    "turn=${event.turnId} id=${event.call.id} success=${event.success} blocked=${event.blocked}",
                )
                finishToolStatus(event.call, event.success && !event.blocked)
                if (!toolRegistry.isReasoningEscalation(event.call)) {
                    store.addToolResult(
                        turnId = event.turnId,
                        call = event.call,
                        result = event.result,
                        success = event.success && !event.blocked,
                    )
                }
            }
            is AgentEvent.TurnFinished -> DiagLog.i(
                "agent.turn.finished",
                "turn=${event.turnId} chars=${event.finalText.length}",
            )
            is AgentEvent.AgentFailed -> DiagLog.w(
                "agent.loop.failed",
                "turn=${event.turnId} error=${event.error}",
            )
            is AgentEvent.MessageFinished -> {
                val persistentCalls = event.message.toolCalls.filterNot(toolRegistry::isReasoningEscalation)
                if (persistentCalls.isNotEmpty()) {
                    store.addLlmMessage(
                        event.message.copy(
                            reasoningContent = null,
                            toolCalls = persistentCalls,
                        ),
                    )
                }
            }
            is AgentEvent.AgentFinished,
            is AgentEvent.ContentDelta,
            is AgentEvent.MessageStarted,
            is AgentEvent.ReasoningDelta,
            is AgentEvent.ToolProgress -> Unit
        }
    }

    private suspend fun executeToolCall(
        call: CloudSpeechClient.ToolCall,
    ): AgentLoop.ToolExecution {
        if (call.name == MainToolRegistry.TOOL_PROTOCOL_REPAIR) {
            emitLog("检测到无效工具协议，要求模型使用原生 tool_calls 重试")
            return AgentLoop.ToolExecution(
                message = CloudSpeechClient.LlmMessage(
                    role = "tool",
                    content = "未受支持的工具调用格式。请立即使用 API 提供的原生 tool_calls 重新输出；不要在正文中输出 XML、JSON、代码块、工具标签或解释文字。如果原内容不是工具调用而是展示资料，请用 Markdown 三反引号围栏包裹，并在围栏外提供一句自然语言结论。",
                    toolCallId = call.id,
                ),
                succeeded = false,
            )
        }
        val title = "${toolRegistry.displayName(call.name)}"
        emitLog("调用工具：$title args=${call.arguments.take(300)}")

        val execution = toolRegistry.execute(call)
        val result = execution.result
        emitLog("工具结果: ${result.contextText}")
        return AgentLoop.ToolExecution(
            message = CloudSpeechClient.LlmMessage(
                role = "tool",
                content = result.contextText.take(MAX_TOOL_RESULT_CHARS),
                toolCallId = call.id,
            ),
            succeeded = result.success,
        )
    }

    private fun blockedToolCall(
        call: CloudSpeechClient.ToolCall,
        reason: String,
    ): CloudSpeechClient.LlmMessage {
        val status = "已阻止重复工具调用：${toolRegistry.displayName(call.name)}"
        emitLog("$status id=${call.id}")
        return CloudSpeechClient.LlmMessage(
            role = "tool",
            content = reason,
            toolCallId = call.id,
        )
    }

    private fun startToolStatus(call: CloudSpeechClient.ToolCall, displayName: String) {
        val text = compactToolLabel(call, displayName)
        val stored = store.addMessage(
            role = "tool",
            content = text,
            toolCallId = call.id,
            toolStatus = ToolDisplayStatus.RUNNING,
        )
        toolStatusMessageIds[call.id] = stored.id
        EventBus.emitChatMessage(
            ChatMessage(
                role = ChatRole.SYSTEM,
                text = text,
                toolCallId = call.id,
                toolStatus = ToolDisplayStatus.RUNNING,
            ),
        )
    }

    private fun finishToolStatus(call: CloudSpeechClient.ToolCall, success: Boolean) {
        val text = compactToolLabel(call, toolRegistry.displayName(call.name))
        val status = if (success) ToolDisplayStatus.SUCCEEDED else ToolDisplayStatus.FAILED
        val messageId = toolStatusMessageIds.remove(call.id)
        if (messageId != null) {
            store.updateMessage(messageId, text, toolStatus = status)
        } else {
            store.addMessage("tool", text, toolCallId = call.id, toolStatus = status)
        }
        EventBus.emitChatMessage(
            ChatMessage(
                role = ChatRole.SYSTEM,
                text = text,
                toolCallId = call.id,
                toolStatus = status,
            ),
        )
    }

    private fun compactToolLabel(call: CloudSpeechClient.ToolCall, displayName: String): String {
        val summary = toolRegistry.displaySummary(call)
        return if (summary.isNullOrBlank()) "🔧 $displayName" else "🔧 $displayName · $summary"
    }

    private fun normalizeLegacyMessage(message: CloudSpeechClient.LlmMessage): CloudSpeechClient.LlmMessage {
        if (message.toolCalls.isNotEmpty()) return message
        val raw = message.content.orEmpty()
        if (!StructuredOutputParser.containsStructuredProtocol(raw)) return message
        if (StructuredOutputParser.containsToolProtocol(raw)) {
            return message.copy(
                content = "",
                toolCalls = listOf(
                    CloudSpeechClient.ToolCall(
                        id = "repair_${UUID.randomUUID()}",
                        name = MainToolRegistry.TOOL_PROTOCOL_REPAIR,
                        arguments = "{}",
                    ),
                ),
            )
        }
        val parsed = StructuredOutputParser.parse(raw)
        return message.copy(content = parsed.speakText, toolCalls = emptyList())
    }

    private suspend fun streamModelTurn(
        client: CloudSpeechClient,
        request: CloudSpeechClient.ChatRequest,
        beforeSpeech: suspend () -> Unit,
        onStreamEvent: (CloudSpeechClient.ChatStreamEvent) -> Unit,
    ): AgentLoop.ModelTurn = coroutineScope {
        val allowDirectSpeech = shouldStreamDirectSpeech(request)
        val extractor = StreamingSpeechExtractor()
        val segmenter = SpeechSegmenter()
        val ttsQueue = Channel<String>(Channel.UNLIMITED)
        val startedAt = System.currentTimeMillis()
        var firstDeltaLogged = false
        var firstSegmentLogged = false
        var reasoningStarted = false
        var streamedSpeech = false

        val promptChars = request.messages.sumOf { message -> message.content.orEmpty().length }
        val systemHash = Integer.toHexString(request.messages.firstOrNull()?.content.orEmpty().hashCode())
        val toolsHash = Integer.toHexString(
            request.tools.joinToString("|") { tool ->
                "${tool.name}:${tool.description}:${tool.parameters}"
            }.hashCode(),
        )
        Timber.i(
            "Latency LLM request_start thinking=${request.thinkingMode} " +
                "messages=${request.messages.size} promptChars=$promptChars " +
                "systemHash=$systemHash toolsHash=$toolsHash tools=${request.tools.size}",
        )
        val playbackJob = launch {
            val playbackSession = StreamingTtsPlaybackSession(client)
            try {
                var speechGateOpened = false
                var segment = ttsQueue.receiveCatching().getOrNull()
                var prepared: PreparedTtsAudio? = null
                while (segment != null) {
                    if (!speechGateOpened) {
                        beforeSpeech()
                        speechGateOpened = true
                    }
                    streamedSpeech = true
                    emitLog("播报: $segment")
                    val nextPrefetch = async(Dispatchers.IO) {
                        ttsQueue.receiveCatching().getOrNull()?.let { next ->
                            next to playbackSession.prepareSentence(next)
                        }
                    }
                    val played = if (prepared != null) {
                        playbackSession.playPrepared(prepared!!)
                    } else {
                        playbackSession.playSentence(segment!!)
                    }
                    if (!played) {
                        playFullTtsSentence(client, segment)
                    }
                    val next = nextPrefetch.await()
                    segment = next?.first
                    prepared = next?.second
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
                        if (allowDirectSpeech) {
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
                    }
                    is CloudSpeechClient.ChatStreamEvent.ToolCallDelta -> Unit
                    is CloudSpeechClient.ChatStreamEvent.Finished -> Unit
                }
            }
            if (allowDirectSpeech) {
                val tail = extractor.finish()
                for (segment in segmenter.feed(tail)) {
                    ttsQueue.send(segment)
                }
                segmenter.flush()?.let { segment ->
                    ttsQueue.send(segment)
                }
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

    private fun shouldStreamDirectSpeech(request: CloudSpeechClient.ChatRequest): Boolean {
        // Deep reasoning and post-tool final answers are still user-visible text.
        // Reasoning deltas and native tool calls are parsed separately and never enter this path.
        if (request.thinkingMode == CloudSpeechClient.ThinkingMode.ENABLED) return true
        if (request.messages.any { it.role == "tool" }) return true
        val recentText = request.messages
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(4)
            .joinToString("\n") { it.content.orEmpty() }
        val normalized = recentText.lowercase(Locale.ROOT)
        return TECHNICAL_SPEECH_HINTS.none(normalized::contains)
    }

    private fun optimizeSpokenReply(displayText: String): String {
        val startedAt = System.currentTimeMillis()
        val spoken = SpokenReplyPolicy.fallback(displayText)
        Timber.i(
            "Latency speech rewrite_local elapsed=${System.currentTimeMillis() - startedAt}ms " +
                "displayChars=${displayText.length} speechChars=${spoken.length}",
        )
        return spoken
    }

    private fun buildMessages(
        userText: String,
        currentUserMessageId: String,
        source: String,
        turnNote: String? = null,
    ): List<CloudSpeechClient.LlmMessage> = buildList {
        add(CloudSpeechClient.LlmMessage("system", buildMainSystemPrompt()))
        val runtimeContext = buildString {
            append("Agent 虚拟文件系统：\n")
            append(executionEnv.virtualRootSummary())
            append("\n\nSkill 索引：\n")
            append(skillRegistry.promptSummary())
            append("\n\n可用凭据 profile（仅可引用名称，认证值不会进入上下文）：\n")
            append(executionEnv.credentialProfileSummary())
            append("\n\n本地上下文：\n")
            append(store.contextSummary())
        }.trim()
        add(CloudSpeechClient.LlmMessage("system", runtimeContext))
        addAll(store.llmHistory(excludeMessageId = currentUserMessageId))
        val timestamp = ZonedDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE XXX", Locale.CHINA),
        )
        add(
            CloudSpeechClient.LlmMessage(
                role = "user",
                content = buildCurrentTurnUserContent(
                    userText = userText,
                    timestamp = timestamp,
                    source = when (source) {
                        "voice" -> "语音"
                        "text" -> "文字"
                        else -> source
                    },
                    network = currentNetworkLabel(),
                    recentUserTiming = store.recentUserTimingSummary(),
                    turnNote = turnNote,
                ),
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun currentNetworkLabel(): String = runCatching {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@runCatching "无网络"
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching "未知"
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularNetworkLabel()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙网络"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其他网络"
        }
    }.getOrElse { "未知" }

    @SuppressLint("MissingPermission")
    private fun cellularNetworkLabel(): String = runCatching {
        val manager = getSystemService(TelephonyManager::class.java)
        when (manager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            else -> "蜂窝网络"
        }
    }.getOrElse { "蜂窝网络" }

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
                MainMediaLibraryService.publishNowPlaying(this@VoiceAgentService, sentence, "正在播报")
                if (playbackSession.playSentence(sentence)) {
                    streamedAny = true
                } else {
                    playFullTtsSentence(client, sentence)
                }
            }
        } finally {
            playbackSession.finish()
            MainMediaLibraryService.publishState(this, active = !dormant, status = if (dormant) "休眠中" else "聆听中")
        }
    }

    private suspend fun playThinkingFeedback() {
        val resourceId = synchronized(thinkingFeedbackLock) {
            val previous = lastThinkingFeedbackAudio
            val available = THINKING_FEEDBACK_AUDIO.filterNot { it == previous }
            available.random(Random.Default).also { lastThinkingFeedbackAudio = it }
        }
        val resourceName = resources.getResourceEntryName(resourceId)
        val audioBytes = withContext(Dispatchers.IO) {
            resources.openRawResource(resourceId).use { it.readBytes() }
        }
        emitLog("思考反馈音: $resourceName")
        playAudio(CloudSpeechClient.AudioPayload(audioBytes, "audio/wav"))
    }

    private suspend fun handleLocalConversationCommand(text: String): Boolean {
        val normalized = text.trim().lowercase()
        val compact = normalized.replace(" ", "")
        val isNew = compact == "/new" ||
            compact in setOf("开启新话题", "新建会话", "新开话题", "重新开始一个话题", "重新开始")
        if (!isNew) return false

        store.startNewConversation(reason = text)
        locationProvider.refreshInBackground("new_topic")
        EventBus.emitChatReset(emptyList())
        emitLog("已开启新话题，准备主动问候")

        val client = ensureSpeechClient()
        if (client == null) {
            emitSessionGreetingFallback(null)
            return true
        }

        updateNotification("正在问候...")
        return try {
            runAgentLoop(
                client = client,
                messages = buildMessages(
                    userText = SESSION_GREETING_TRIGGER,
                    currentUserMessageId = SESSION_GREETING_MESSAGE_ID,
                    source = "session",
                ),
                initialThinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
                maxToolRounds = 1,
                allowReasoningEscalation = false,
                toolsEnabled = false,
            )
            updateNotification(if (dormant) "休眠中，等待唤醒" else "聆听中...")
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Session greeting failed")
            emitLog("新话题问候失败，使用本地问候")
            emitSessionGreetingFallback(client)
            true
        }
    }

    private suspend fun emitSessionGreetingFallback(client: CloudSpeechClient?) {
        val greeting = "你好，新的话题开始了。想聊什么？"
        store.addMessage("assistant", greeting)
        EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, greeting))
        emitLog("助手: $greeting")
        if (client != null) {
            runCatching { speakAssistantText(client, greeting) }
                .onFailure { Timber.w(it, "Session greeting fallback TTS failed") }
        }
        updateNotification(if (dormant) "休眠中，等待唤醒" else "聆听中...")
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
        val audio = try {
            client.synthesizeSpeech(sentence).also {
                Timber.i(
                    "TTS full response bytes=${it.bytes.size} mime=${it.mimeType} " +
                        "elapsed=${System.currentTimeMillis() - startedAt}ms",
                )
            }
        } catch (error: NetworkTimeoutException) {
            throw error
        } catch (error: Exception) {
            Timber.e(error, "TTS failed for: $sentence")
            null
        }
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
        private var speechStarted = false
        private var pendingTail = ByteArray(0)
        private val focus = requestPlaybackFocus()
        private val startedAt = System.currentTimeMillis()

        suspend fun playSentence(sentence: String): Boolean = withContext(Dispatchers.IO) {
            if (!ENABLE_STREAMING_TTS || sentence.isBlank()) return@withContext false

            var wroteAudio = false
            val result = runCatching {
                Timber.i("Latency TTS stream_start chars=${sentence.length}")
                val streamed = client.streamSynthesizeSpeech(sentence) { payload ->
                    val rawChunk = decodePcmChunk(payload.bytes)
                    if (rawChunk.pcm.isEmpty()) return@streamSynthesizeSpeech
                    if (!firstAudioLogged) {
                        firstAudioLogged = true
                        Timber.i(
                            "Latency TTS first_audio elapsed=${System.currentTimeMillis() - startedAt}ms " +
                            "bytes=${payload.bytes.size} mime=${payload.mimeType}",
                        )
                    }
                    appendPcm(rawChunk.pcm, rawChunk.sampleRate, payload.mimeType)
                    wroteAudio = true
                }
                streamed && wroteAudio && audioTrack != null
              }
              if (result.isFailure) {
                  val error = result.exceptionOrNull()
                  if (error is CancellationException || error is NetworkTimeoutException) {
                      throw error
                  }
                  Timber.w(result.exceptionOrNull(), "Streaming TTS failed, fallback to full audio")
                  finish()
                  false
            } else {
                result.getOrDefault(false)
            }
        }

        suspend fun prepareSentence(sentence: String): PreparedTtsAudio? = withContext(Dispatchers.IO) {
            if (!ENABLE_STREAMING_TTS || sentence.isBlank()) return@withContext null

            val output = ByteArrayOutputStream()
            var sentenceSampleRate = sampleRate
            var mimeType: String? = null
            val result = runCatching {
                Timber.i("Latency TTS prefetch_start chars=${sentence.length}")
                val streamed = client.streamSynthesizeSpeech(sentence) { payload ->
                    val rawChunk = decodePcmChunk(payload.bytes)
                    if (rawChunk.pcm.isEmpty()) return@streamSynthesizeSpeech
                    sentenceSampleRate = rawChunk.sampleRate
                    mimeType = payload.mimeType
                    output.write(amplifyPcm16Le(rawChunk.pcm))
                }
                if (streamed && output.size() > 0) {
                    Timber.i(
                        "Latency TTS prefetch_ready chars=${sentence.length} " +
                            "bytes=${output.size()} sampleRate=$sentenceSampleRate",
                    )
                    PreparedTtsAudio(output.toByteArray(), sentenceSampleRate, mimeType)
                } else {
                    null
                }
            }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error is CancellationException || error is NetworkTimeoutException) throw error
                Timber.w(error, "TTS prefetch failed")
            }
            result.getOrNull()
        }

        suspend fun playPrepared(prepared: PreparedTtsAudio): Boolean = withContext(Dispatchers.IO) {
            if (prepared.pcm.isEmpty()) return@withContext false
            appendPcm(prepared.pcm, prepared.sampleRate, prepared.mimeType, alreadyBoosted = true)
            true
        }

        suspend fun finish() = withContext(Dispatchers.IO) {
            val track = audioTrack
            if (track != null) {
                flushPendingTail(track)
            }
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

        private fun appendPcm(
            rawPcm: ByteArray,
            chunkSampleRate: Int,
            mimeType: String?,
            alreadyBoosted: Boolean = false,
        ) {
            if (rawPcm.isEmpty()) return
            val pcm = if (alreadyBoosted) rawPcm else amplifyPcm16Le(rawPcm)
            val track = ensureTrack(chunkSampleRate, mimeType, rawPcm, pcm)
            if (!speechStarted) {
                fadeInPcm16LeInPlace(pcm, chunkSampleRate)
                speechStarted = true
            }

            val combined = concatBytes(pendingTail, pcm)
            val tailBytes = fadeByteCount(chunkSampleRate).coerceAtMost(combined.size)
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

        private fun flushPendingTail(track: AudioTrack) {
            if (pendingTail.isEmpty()) return
            fadeOutPcm16LeInPlace(pendingTail, sampleRate)
            writePcm(track, pendingTail, 0, pendingTail.size)
            pendingTail = ByteArray(0)
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

    private data class PreparedTtsAudio(
        val pcm: ByteArray,
        val sampleRate: Int,
        val mimeType: String?,
    )

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
            telecomSession.endListening("already_dormant_cleanup")
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
        MainMediaLibraryService.publishState(this, active = false, status = "休眠中")
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
        ensureForeground("休眠中，等待唤醒", microphoneActive = false)
    }

    private fun ensureForegroundForCurrentState() {
        ensureForeground(
            text = if (dormant) "休眠中，等待唤醒" else "聆听中...",
            microphoneActive = !dormant,
        )
    }

    private fun ensureForeground(text: String, microphoneActive: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (microphoneActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(
                NOTIFICATION_ID,
                buildNotification(text),
                types,
            )
            DiagLog.i(
                "service.foreground_type",
                "microphone=$microphoneActive types=$types dormant=$dormant",
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text))
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

    private suspend fun handleConnectionLost(operation: String, error: NetworkTimeoutException) {
        val message = "本轮网络响应超时，请稍后重试。助手仍保持在线。"
        Timber.w(error, "Network connection lost during $operation")
        DiagLog.w(
            "network.connection_lost",
            "operation=$operation reason=${error.message}",
            showInUi = true,
        )
        store.addMessage("system", message)
        EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, message))
        emitLog(message)
        earcons.error()
        updateNotification(if (dormant) "休眠中，等待唤醒" else "聆听中...")
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(actionIcon, actionText, controlPi)
            .setOngoing(true)

        if (ENABLE_LEGACY_MEDIA_SESSION) {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0),
            )
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
        MainMediaLibraryService.publishState(this, active = !dormant, status = text)
    }

}
