package com.agent.voiceassistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.os.IBinder
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.agent.voiceassistant.MainActivity
import com.agent.voiceassistant.R
import com.agent.voiceassistant.agent.LLMConfig
import com.agent.voiceassistant.agent.LocalConversationCommandPolicy
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
import com.agent.voiceassistant.cloud.LlmClient
import com.agent.voiceassistant.cloud.MultimodalImageEncoder
import com.agent.voiceassistant.cloud.ListeningInactivityPolicy
import com.agent.voiceassistant.cloud.OpenAiCompatibleLlmClient
import com.agent.voiceassistant.cloud.NetworkTimeoutException
import com.agent.voiceassistant.cloud.SpeechSegmenter
import com.agent.voiceassistant.cloud.SimpleVadRecorder
import com.agent.voiceassistant.cloud.StreamingSpeechExtractor
import com.agent.voiceassistant.cloud.VoiceReplyDirective
import com.agent.voiceassistant.cloud.VoiceReplyDirectiveParser
import com.agent.voiceassistant.cloud.VoiceReplyMode
import com.agent.voiceassistant.cloud.VoiceReplyOptions
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.data.StoredAttachment
import com.agent.voiceassistant.media.MainMediaLibraryService
import com.agent.voiceassistant.media.AssistantNotificationContract
import com.agent.voiceassistant.settings.LlmProviderRepository
import com.agent.voiceassistant.settings.SpeechPreferences
import com.agent.voiceassistant.tools.LocalToolExecutor
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import com.agent.voiceassistant.tools.CodeGraphIndex
import com.agent.voiceassistant.tools.LocationProvider
import com.agent.voiceassistant.tools.MainToolRegistry
import com.agent.voiceassistant.ui.ChatMessage
import com.agent.voiceassistant.ui.ChatPresentation
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class VoiceAgentService : Service() {

    companion object {
        private const val STREAM_TTS_SAMPLE_RATE = 24_000
        private const val INITIAL_STREAM_BUFFER_BYTES = 9_600
        private const val ENABLE_STREAMING_TTS = true
        private const val ENABLE_PLAYBACK_DONE_EARCON = false
        private const val SLOW_NETWORK_FEEDBACK_MS = 3_000L
        private const val TOTAL_INACTIVITY_SLEEP_MS = 15_000L
        private const val TTS_FADE_MS = 18
        private const val TTS_FINAL_SILENCE_MS = 90
        private const val DEEP_MAX_TOOL_ROUNDS = 10
        private const val FAST_MAX_COMPLETION_TOKENS = 1_024
        private const val DEEP_MAX_COMPLETION_TOKENS = 4_096
        private const val MAX_TOOL_RESULT_CHARS = 12_000
        private const val MAX_IMAGE_HISTORY_TURNS = 3
        private const val MAX_IMAGE_INPUTS = 4
        private const val LEGACY_STREAM_BLUETOOTH_SCO = 6
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        private const val SESSION_GREETING_MESSAGE_ID = "__session_greeting__"
        private const val SESSION_GREETING_TRIGGER =
            "这是 Main Agent 的内部会话事件：用户刚刚开启了一个新话题。请只向用户发送一句简短、自然的中文问候，并邀请用户提出新的话题。不要调用工具，不要提及这个内部事件。"
        private val THINKING_FEEDBACK_AUDIO = intArrayOf(
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
        private val PERSONALIZED_VOICE_HINTS = listOf(
            "唱歌", "唱一", "唱首", "换个声音", "换一种声音", "换个音色", "音色",
            "模仿", "搞怪语气", "特殊语气", "配音", "声音设计",
        )

        const val ACTION_START = "com.agent.voiceassistant.START"
        const val ACTION_BOOTSTRAP = "com.agent.voiceassistant.BOOTSTRAP"
        const val ACTION_STOP = "com.agent.voiceassistant.STOP"
        const val ACTION_WAKE = "com.agent.voiceassistant.WAKE"
        const val ACTION_SLEEP = "com.agent.voiceassistant.SLEEP"
        const val ACTION_TEXT_INPUT = "com.agent.voiceassistant.TEXT_INPUT"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_ATTACHMENTS = "attachments"

        fun start(ctx: Context) {
            DiagLog.i("api.start", "ctx=${ctx.javaClass.simpleName}")
            val intent = Intent(ctx, VoiceAgentService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun bootstrap(ctx: Context) {
            DiagLog.i("api.bootstrap", "ctx=${ctx.javaClass.simpleName}")
            val intent = Intent(ctx, VoiceAgentService::class.java).setAction(ACTION_BOOTSTRAP)
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
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure { error ->
                DiagLog.w(
                    "api.wake.denied",
                    "${error.javaClass.simpleName}:${error.message}",
                    showInUi = true,
                )
                MainMediaLibraryService.publishState(
                    ctx,
                    active = false,
                    status = "请点击通知或打开 App 后唤醒",
                )
            }
        }

        fun sleep(ctx: Context) {
            DiagLog.i("api.sleep", "ctx=${ctx.javaClass.simpleName}")
            val intent = Intent(ctx, VoiceAgentService::class.java).setAction(ACTION_SLEEP)
            ctx.startService(intent)
        }

        fun sendText(ctx: Context, text: String, attachments: List<String> = emptyList()) {
            val intent = Intent(ctx, VoiceAgentService::class.java)
                .setAction(ACTION_TEXT_INPUT)
                .putExtra(EXTRA_TEXT, text)
                .putStringArrayListExtra(EXTRA_ATTACHMENTS, ArrayList(attachments))
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
    private lateinit var store: ConversationStore
    private lateinit var llmProviderRepository: LlmProviderRepository
    private lateinit var speechPreferences: SpeechPreferences
    private lateinit var locationProvider: LocationProvider
    private lateinit var toolRegistry: MainToolRegistry
    private lateinit var executionEnv: AndroidExecutionEnv
    private lateinit var skillRegistry: SkillRegistry
    private lateinit var imageEncoder: MultimodalImageEncoder
    private val agentHarness = MainAgentHarness()
    private lateinit var earcons: EarconPlayer
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
        llmProviderRepository = LlmProviderRepository(this)
        speechPreferences = SpeechPreferences(this)
        locationProvider = LocationProvider(this, store)
        executionEnv = AndroidExecutionEnv(this)
        skillRegistry = SkillRegistry(
            executionEnv.skillsRoot,
            executionEnv.disabledSkillsRoot,
            executionEnv.deletedSkillsManifest,
            executionEnv.modifiedSkillsManifest,
        )
        imageEncoder = MultimodalImageEncoder(executionEnv.workspaceRoot)
        toolRegistry = MainToolRegistry(
            LocalToolExecutor(
                store = store,
                locationProvider = locationProvider,
                executionEnv = executionEnv,
                codeGraph = CodeGraphIndex(this),
                skillRegistry = skillRegistry,
            ),
        )
        locationProvider.refreshInBackground("service_start")
        earcons = EarconPlayer { routeManager }
        createNotificationChannel()
        MainMediaLibraryService.ensureStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagLog.i(
            "service.start_command",
            "action=${intent?.action} startId=$startId dormant=$dormant loop=${loopJob?.isActive == true}",
            showInUi = true,
        )
        when (intent?.action) {
            null -> sleepAgent()
            ACTION_BOOTSTRAP -> sleepAgent()
            ACTION_START, ACTION_WAKE -> wakeAgent()
            ACTION_SLEEP -> sleepAgent()
            ACTION_TEXT_INPUT -> {
                ensureForegroundForCurrentState()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val attachments = intent.getStringArrayListExtra(EXTRA_ATTACHMENTS).orEmpty()
                if (text.isNotBlank()) {
                    serviceScope.launch { processUserText(text.trim(), source = "text", attachments = attachments) }
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
        serviceScope.cancel()
        locationProvider.close()
        super.onDestroy()
    }

    private fun wakeAgent() {
        DiagLog.i("agent.wake.begin", "dormant=$dormant loop=${loopJob?.isActive == true}", showInUi = true)
        if (loopJob?.isActive == true && !dormant) return
        val foregroundReady = runCatching {
            ensureForeground("唤醒中...", microphoneActive = true)
        }.onFailure { error ->
            DiagLog.w(
                "agent.wake.foreground_denied",
                "${error.javaClass.simpleName}:${error.message}",
                showInUi = true,
            )
        }.isSuccess
        if (!foregroundReady) {
            dormant = true
            _state.value = State.READY
            emitState(ServiceState.DORMANT)
            emitLog("系统不允许从当前后台状态启动麦克风，请点击通知或打开 App 后重试")
            MainMediaLibraryService.publishState(this, active = false, status = "点击 App 后唤醒")
            ensureDormantForeground()
            serviceScope.launch { earcons.error() }
            return
        }

        val config = LLMConfig.auto()
        if (config.apiKey.isBlank()) {
            DiagLog.w("agent.wake.fail", "missing_api_key", showInUi = true)
            fail("未配置 LLM_API_KEY")
            return
        }

        dormant = false
        MainMediaLibraryService.publishState(this, active = true, status = "聆听中")
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

    @SuppressLint("MissingPermission")
    private suspend fun runConversationLoop() {
        while (coroutineContext.isActive && !dormant) {
            try {
                val readiness = routeManager?.awaitVoiceRoute()
                if (readiness?.ready == false) {
                    handleMicrophoneFailure(readiness.summary)
                    break
                }
                readiness?.let {
                    DiagLog.i(
                        "audio.route.ready",
                        "elapsedMs=${it.elapsedMs} ${it.summary.take(140)}",
                        showInUi = true,
                    )
                }
                updateNotification("聆听中...")
                emitState(ServiceState.LISTENING)
                emitLog("请说话")
                earcons.listening()
                val inactivityStartedAt = SystemClock.elapsedRealtime()
                when (val capture = recorder?.recordNextUtterance() ?: SimpleVadRecorder.CaptureResult.Stopped) {
                    is SimpleVadRecorder.CaptureResult.Recorded -> {
                        handleRecordedCapture(capture.recording)
                    }
                    is SimpleVadRecorder.CaptureResult.RouteUnavailable -> {
                        handleMicrophoneFailure(capture.summary)
                    }
                    SimpleVadRecorder.CaptureResult.InactivityWarning -> {
                        emitLog("10 秒未检测到语音，即将进入休眠")
                        updateNotification("即将进入休眠...")
                        val announcementBudgetMs = ListeningInactivityPolicy.remainingUntilSleep(
                            totalMs = TOTAL_INACTIVITY_SLEEP_MS,
                            elapsedMs = SystemClock.elapsedRealtime() - inactivityStartedAt,
                        )
                        val announcementFinished = withTimeoutOrNull(announcementBudgetMs) {
                            announceInactivitySleep()
                            true
                        } ?: false
                        if (!announcementFinished) {
                            emitLog("即将休眠播报达到总超时截止点")
                        }
                        val remainingMs = ListeningInactivityPolicy.remainingUntilSleep(
                            totalMs = TOTAL_INACTIVITY_SLEEP_MS,
                            elapsedMs = SystemClock.elapsedRealtime() - inactivityStartedAt,
                        )
                        val followUp = if (remainingMs == 0L) {
                            SimpleVadRecorder.CaptureResult.InactivitySleep
                        } else {
                            recorder?.recordNextUtterance(
                                inactivitySleepMs = remainingMs,
                                warningAlreadyPlayed = true,
                            ) ?: SimpleVadRecorder.CaptureResult.Stopped
                        }
                        when (followUp) {
                            is SimpleVadRecorder.CaptureResult.Recorded -> {
                                handleRecordedCapture(followUp.recording)
                            }
                            is SimpleVadRecorder.CaptureResult.RouteUnavailable -> {
                                handleMicrophoneFailure(followUp.summary)
                            }
                            SimpleVadRecorder.CaptureResult.InactivitySleep -> {
                                emitLog("休眠提示后 5 秒仍未检测到语音，进入休眠")
                                sleepAgent()
                            }
                            SimpleVadRecorder.CaptureResult.Stopped -> break
                            SimpleVadRecorder.CaptureResult.InactivityWarning -> Unit
                        }
                    }
                    SimpleVadRecorder.CaptureResult.InactivitySleep -> {
                        emitLog("持续未检测到语音，进入休眠")
                        sleepAgent()
                    }
                    SimpleVadRecorder.CaptureResult.Stopped -> break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NetworkTimeoutException) {
                handleConnectionLost("voice", e)
                continue
            } catch (e: IOException) {
                handleConnectionLost("voice", NetworkTimeoutException("voice network", e))
                continue
            } catch (e: Exception) {
                Timber.e(e, "Voice loop failed")
                emitLog("本轮失败: ${e.message}")
                earcons.error()
            }
        }
    }

    private suspend fun handleRecordedCapture(recording: SimpleVadRecorder.Recording) {
        updateNotification("识别中...")
        emitLog("录音完成 ${recording.durationMs}ms，停止收音")
        serviceScope.launch {
            runCatching { earcons.captureDone() }
                .onFailure { Timber.w(it, "Capture-done earcon failed") }
        }
        processTurn(recording)
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
        attachments: List<String> = emptyList(),
    ) {
        turnMutex.withLock {
            try {
                if (handleLocalConversationCommand(userText)) return
                val client = ensureSpeechClient() ?: return
                val llmClient = createLlmClient()

                val displayText = buildString {
                    append(userText)
                    if (attachments.isNotEmpty()) {
                        append("\n")
                        attachments.forEach { append("\n附件：$it") }
                    }
                }
                val storedAttachments = attachments.map { StoredAttachment(it, attachmentMimeType(it)) }
                val currentUserMessage = store.addMessage("user", displayText, attachments = storedAttachments)
                EventBus.emitChatMessage(ChatMessage(ChatRole.USER, displayText))
                turnNote?.let {
                    store.addMessage("system", it)
                    EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, it))
                }
                emitLog("你($source): $userText attachments=${attachments.size}")
                updateNotification("正在回应...")

                val outcome = runAgentLoop(
                    llmClient = llmClient,
                    speechClient = client,
                    messages = buildMessages(
                        userText = userText,
                        currentUserMessageId = currentUserMessage.id,
                        source = source,
                        turnNote = turnNote,
                        attachments = attachments,
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
            } catch (e: IOException) {
                handleConnectionLost("agent", NetworkTimeoutException("agent network", e))
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
        llmClient: LlmClient,
        speechClient: CloudSpeechClient,
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
                    llmClient = llmClient,
                    speechClient = speechClient,
                    request = request,
                    beforeSpeech = {
                        awaitReasoningFeedback()
                        beforeSpeech()
                    },
                    onStreamEvent = onStreamEvent,
                )

                override fun normalizeAssistant(message: CloudSpeechClient.LlmMessage) =
                    normalizeLegacyMessage(message)

                override fun isTerminalPresentation(call: CloudSpeechClient.ToolCall) =
                    toolRegistry.isTerminalPresentation(call)

                override suspend fun executeTerminalPresentation(
                    call: CloudSpeechClient.ToolCall,
                ): AgentLoop.TerminalExecution {
                    if (toolRegistry.isAgentSleep(call)) {
                        val finalText = "好的，我先休眠了。"
                        store.addMessage("assistant", finalText)
                        EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, finalText))
                        emitLog("语义休眠：用户明确结束交互")
                        DiagLog.i("agent.semantic_sleep", "toolCallId=${call.id}", showInUi = true)
                        sleepAgent(cancelConversationLoop = false)
                        return AgentLoop.TerminalExecution(
                            result = AgentLoop.ToolExecution(
                                message = CloudSpeechClient.LlmMessage(
                                    role = "tool",
                                    content = "助手已进入休眠",
                                    toolCallId = call.id,
                                ),
                                succeeded = true,
                            ),
                            finalText = finalText,
                            playedSpeech = false,
                        )
                    }
                    return runCatching {
                        val directive = VoiceReplyDirectiveParser.parse(call.arguments)
                        awaitReasoningFeedback()
                        beforeSpeech()
                        playVoiceReply(speechClient, directive)
                        store.addMessage(
                            role = "assistant",
                            content = directive.text,
                            presentation = ChatPresentation.PERSONALIZED_VOICE,
                        )
                        EventBus.emitChatMessage(
                            ChatMessage(
                                role = ChatRole.BOT,
                                text = directive.text,
                                presentation = ChatPresentation.PERSONALIZED_VOICE,
                            ),
                        )
                        emitLog("个性化播报: ${directive.text}")
                        AgentLoop.TerminalExecution(
                            result = AgentLoop.ToolExecution(
                                message = CloudSpeechClient.LlmMessage(
                                    role = "tool",
                                    content = "个性化播报已完成",
                                    toolCallId = call.id,
                                ),
                                succeeded = true,
                            ),
                            finalText = directive.text,
                            playedSpeech = true,
                        )
                    }.getOrElse { error ->
                        Timber.w(error, "voice_reply rejected")
                        AgentLoop.TerminalExecution(
                            result = AgentLoop.ToolExecution(
                                message = CloudSpeechClient.LlmMessage(
                                    role = "tool",
                                    content = "voice_reply 调用失败：${error.message}。请修正参数并重新调用；不要输出普通正文。",
                                    toolCallId = call.id,
                                ),
                                succeeded = false,
                            ),
                        )
                    }
                }

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
                        speakAssistantText(speechClient, optimizeSpokenReply(finalText))
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
            llmClient.close()
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
                val persistentCalls = if (event.message.toolCalls.any(toolRegistry::isTerminalPresentation)) {
                    emptyList()
                } else {
                    event.message.toolCalls.filterNot(toolRegistry::isReasoningEscalation)
                }
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
        emitLog("工具结果: ${compactDiagnosticText(result.contextText)}")
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
        llmClient: LlmClient,
        speechClient: CloudSpeechClient,
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
        val firstModelEvent = AtomicBoolean(false)
        val waitFeedbackPlayed = AtomicBoolean(false)
        val slowNetworkFeedback = launch {
            delay(SLOW_NETWORK_FEEDBACK_MS)
            if (!firstModelEvent.get() && waitFeedbackPlayed.compareAndSet(false, true)) {
                DiagLog.i("network.wait_feedback", "elapsedMs=$SLOW_NETWORK_FEEDBACK_MS")
                emitLog("网络响应较慢，仍在等待")
                earcons.waiting()
            }
        }

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
            val playbackSession = StreamingTtsPlaybackSession(speechClient)
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
                        playFullTtsSentence(speechClient, segment)
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
            val completion = llmClient.streamChat(request) { event ->
                firstModelEvent.set(true)
                slowNetworkFeedback.cancel()
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
            slowNetworkFeedback.cancel()
            ttsQueue.close()
        }
    }

    private fun shouldStreamDirectSpeech(request: CloudSpeechClient.ChatRequest): Boolean {
        // Deep reasoning and post-tool final answers are still user-visible text.
        // Reasoning deltas and native tool calls are parsed separately and never enter this path.
        val latestUserText = request.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (
            request.tools.any { it.name == MainToolRegistry.TOOL_VOICE_REPLY } &&
            PERSONALIZED_VOICE_HINTS.any(latestUserText::contains)
        ) {
            return false
        }
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

    private suspend fun buildMessages(
        userText: String,
        currentUserMessageId: String,
        source: String,
        turnNote: String? = null,
        attachments: List<String> = emptyList(),
    ): List<CloudSpeechClient.LlmMessage> {
        val history = store.llmHistory(excludeMessageId = currentUserMessageId)
        val currentAttachmentPaths = attachments.filter(::isImageAttachment)
        val providerSupportsImages = llmProviderRepository.activeProfile().supportsImages
        val selectedImagePaths = if (providerSupportsImages) {
            (history.filter { it.role == "user" && it.attachmentPaths.any(::isImageAttachment) }
                .takeLast(MAX_IMAGE_HISTORY_TURNS)
                .flatMap { it.attachmentPaths.filter(::isImageAttachment) } + currentAttachmentPaths)
                .takeLast(MAX_IMAGE_INPUTS)
                .toSet()
        } else {
            emptySet()
        }
        val hydratedHistory = history.map { message -> hydrateImages(message, selectedImagePaths) }
        val currentImages = if (providerSupportsImages) {
            currentAttachmentPaths.filter { it in selectedImagePaths }.mapNotNull { path ->
                runCatching { imageEncoder.encode(path) }
                    .onFailure { Timber.w(it, "Image encoding failed path=$path") }
                    .getOrNull()
            }
        } else {
            emptyList()
        }

        return buildList {
        add(CloudSpeechClient.LlmMessage("system", buildMainSystemPrompt()))
        val runtimeContext = buildString {
            append("Agent 虚拟文件系统：\n")
            append(executionEnv.virtualRootSummary())
            append("\n\n可用凭据 profile（仅可引用名称，认证值不会进入上下文）：\n")
            append(executionEnv.credentialProfileSummary())
            append("\n\n会话上下文资产快照：\n")
            append(store.sessionContextSnapshot(skillRegistry.promptSummary()))
        }.trim()
        add(CloudSpeechClient.LlmMessage("system", runtimeContext))
        addAll(hydratedHistory)
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
                ) + buildString {
                    if (attachments.isNotEmpty()) append("\n本轮附件：\n${attachments.joinToString("\n") { "- $it" }}")
                    if (!providerSupportsImages && currentAttachmentPaths.isNotEmpty()) {
                        append("\n当前聊天模型未启用图片输入能力，只能看到图片路径，不能直接理解画面。")
                    }
                },
                attachmentPaths = attachments,
                imageInputs = currentImages,
            ),
        )
        }
    }

    private suspend fun hydrateImages(
        message: CloudSpeechClient.LlmMessage,
        selectedPaths: Set<String>,
    ): CloudSpeechClient.LlmMessage {
        if (message.role != "user") return message
        val images = message.attachmentPaths.filter { it in selectedPaths }.mapNotNull { path ->
            runCatching { imageEncoder.encode(path) }
                .onFailure { Timber.w(it, "Historical image encoding failed path=$path") }
                .getOrNull()
        }
        return message.copy(imageInputs = images)
    }

    private fun isImageAttachment(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

    private fun attachmentMimeType(path: String): String? = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
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

    private suspend fun announceInactivitySleep() {
        val audioBytes = withContext(Dispatchers.IO) {
            resources.openRawResource(R.raw.inactivity_sleep).use { it.readBytes() }
        }
        emitLog("本地休眠提示：即将休眠")
        playAudio(CloudSpeechClient.AudioPayload(audioBytes, "audio/wav"))
    }

    private suspend fun handleLocalConversationCommand(text: String): Boolean {
        return when (LocalConversationCommandPolicy.classify(text)) {
            LocalConversationCommandPolicy.Command.NEW_TOPIC -> startNewConversation(text)
            LocalConversationCommandPolicy.Command.SLEEP -> handleLocalSleepCommand(text)
            null -> false
        }
    }

    private suspend fun startNewConversation(text: String): Boolean {
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
                llmClient = createLlmClient(),
                speechClient = client,
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

    private fun handleLocalSleepCommand(text: String): Boolean {
        store.addMessage("user", text)
        EventBus.emitChatMessage(ChatMessage(ChatRole.USER, text))
        emitLog("你(local): $text")

        val confirmation = "好的，我先休眠了。"
        store.addMessage("assistant", confirmation)
        EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, confirmation))
        emitLog("本地语义休眠：$text")
        DiagLog.i("agent.semantic_sleep.local", "text=${text.take(40)}", showInUi = true)
        sleepAgent(cancelConversationLoop = false)
        return true
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
        val config = LLMConfig.stepFun()
        if (config.apiKey.isBlank()) {
            fail("未配置 LLM_API_KEY")
            return null
        }
        return CloudSpeechClient(config).also { speechClient = it }
    }

    private fun createLlmClient(): LlmClient =
        OpenAiCompatibleLlmClient(llmProviderRepository.runtimeConfig())

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

    private suspend fun playVoiceReply(
        client: CloudSpeechClient,
        directive: VoiceReplyDirective,
    ) {
        MainMediaLibraryService.publishNowPlaying(this, directive.text, "个性化播报")
        try {
            if (directive.options.mode == VoiceReplyMode.PRESET) {
                val playback = StreamingTtsPlaybackSession(client)
                try {
                    val streamed = playback.playSentence(directive.text, directive.options)
                    if (!streamed) {
                        playAudio(client.synthesizeSpeech(directive.text, directive.options))
                    }
                } finally {
                    playback.finish()
                }
            } else {
                playAudio(client.synthesizeSpeech(directive.text, directive.options))
            }
        } finally {
            MainMediaLibraryService.publishState(
                this,
                active = !dormant,
                status = if (dormant) "休眠中" else "聆听中",
            )
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

        suspend fun playSentence(
            sentence: String,
            options: VoiceReplyOptions = VoiceReplyOptions(),
        ): Boolean = withContext(Dispatchers.IO) {
            if (!ENABLE_STREAMING_TTS || sentence.isBlank()) return@withContext false

            var wroteAudio = false
            val result = runCatching {
                Timber.i("Latency TTS stream_start chars=${sentence.length}")
                val streamed = client.streamSynthesizeSpeech(sentence, options) { payload ->
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
                    "pcm ${describePcm(rawPcm)} -> ${describePcm(boostedPcm)} gain=${currentTtsGain()}",
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
                routeManager?.logTrackRoute(track, "playback_started")
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

        val attributes = playbackAudioAttributes()
        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
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
        logPlaybackAudioDomain(track, attributes)
        return track
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
        if (boostedBytes.startsWith("RIFF")) {
            val pcm = decodePcmChunk(boostedBytes)
            if (pcm.pcm.isNotEmpty()) {
                playPcmAudio(pcm)
                return
            }
        }
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

    private suspend fun playPcmAudio(chunk: PcmChunk) = withContext(Dispatchers.IO) {
        val focus = requestPlaybackFocus()
        val track = createPcmTrack(chunk.sampleRate)
        try {
            routeManager?.applyOutputRouting(track)
            track.play()
            var offset = 0
            while (offset < chunk.pcm.size) {
                coroutineContext.ensureActive()
                val written = track.write(chunk.pcm, offset, chunk.pcm.size - offset)
                if (written <= 0) error("AudioTrack write failed: $written")
                offset += written
            }
            val totalBytes = offset + writeSilenceTail(track, chunk.sampleRate)
            waitForAudioTrack(track, totalBytes, chunk.sampleRate)
        } finally {
            releaseAudioTrack(track)
            abandonPlaybackFocus(focus)
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
        val communicationSession = routeManager != null
        return AudioAttributes.Builder()
            .setUsage(
                if (communicationSession) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_MEDIA
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun logPlaybackAudioDomain(track: AudioTrack, attributes: AudioAttributes) {
        val manager = getSystemService(AudioManager::class.java)
        val communicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.communicationDevice?.type
        } else {
            null
        }
        Timber.i(
            "AudioDomain: usage=${attributes.usage} stream=${track.streamType} mode=${manager.mode} " +
                "communicationDevice=$communicationDevice " +
                "music=${manager.getStreamVolume(AudioManager.STREAM_MUSIC)} " +
                "voice=${manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} " +
                "sco=${manager.getStreamVolume(LEGACY_STREAM_BLUETOOTH_SCO)}",
        )
    }

    private fun boostEncodedAudioIfPossible(bytes: ByteArray): ByteArray {
        val data = findWavDataChunk(bytes)
        if (data == null || data.bitsPerSample != 16 || data.size <= 0) return bytes
        val boosted = bytes.copyOf()
        amplifyPcm16LeInPlace(boosted, data.offset, data.offset + data.size)
        Timber.i(
                "TTS wav sampleRate=${data.sampleRate} pcm " +
                "${describePcm(bytes, data.offset, data.offset + data.size)} -> " +
                "${describePcm(boosted, data.offset, data.offset + data.size)} gain=${currentTtsGain()}",
        )
        return boosted
    }

    private fun amplifyPcm16Le(bytes: ByteArray): ByteArray {
        val boosted = bytes.copyOf()
        amplifyPcm16LeInPlace(boosted, 0, boosted.size)
        return boosted
    }

    private fun amplifyPcm16LeInPlace(bytes: ByteArray, start: Int, endExclusive: Int) {
        val gain = currentTtsGain()
        var i = start
        val end = endExclusive - 1
        while (i < end) {
            val sample = (((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF))).toShort().toInt()
            val boosted = (sample * gain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i] = (boosted and 0xFF).toByte()
            bytes[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun currentTtsGain(): Float =
        if (::speechPreferences.isInitialized) speechPreferences.ttsGain else 1.2f

    private fun compactDiagnosticText(text: String, maxChars: Int = 320): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxChars) compact else compact.take(maxChars - 1) + "…"
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

    private fun sleepAgent(
        keepForeground: Boolean = true,
        cancelConversationLoop: Boolean = true,
    ) {
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
        if (cancelConversationLoop) loopJob?.cancel()
        loopJob = null
        recorder?.stop()
        recorder = null
        player?.let { releasePlayer(it) }
        player = null
        _state.value = State.READY
        emitState(ServiceState.DORMANT)
        emitLog("Agent 已休眠")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (microphoneActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(
                AssistantNotificationContract.NOTIFICATION_ID,
                buildNotification(text),
                types,
            )
            DiagLog.i(
                "service.foreground_type",
                "microphone=$microphoneActive types=$types dormant=$dormant",
            )
        } else {
            startForeground(AssistantNotificationContract.NOTIFICATION_ID, buildNotification(text))
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

    private suspend fun handleMicrophoneFailure(details: String) {
        val message = "麦克风不可用，助手已返回休眠。请检查录音权限或音频设备后重新唤醒。"
        DiagLog.w("audio.microphone.failed", details, showInUi = true)
        store.addMessage("system", message)
        EventBus.emitChatMessage(ChatMessage(ChatRole.SYSTEM, message))
        emitLog(message)
        updateNotification("麦克风不可用")
        earcons.error()
        sleepAgent()
    }

    private fun emitLog(msg: String) {
        Timber.i("LogBus: $msg")
        EventBus.emitLog(msg)
    }

    private fun emitState(state: ServiceState) {
        EventBus.emitState(state)
    }

    private fun requestPlaybackFocus(): AudioFocusRequest? {
        val mgr = getSystemService(AudioManager::class.java)
        routeManager?.ensureCommunicationMode("playback_focus")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = playbackAudioAttributes()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
            val result = mgr.requestAudioFocus(request)
            Timber.i("AudioDomain: focus usage=${attributes.usage} result=$result mode=${mgr.mode}")
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
        AssistantNotificationContract.ensureChannel(this)
    }

    private fun buildNotification(text: String): Notification {
        MainMediaLibraryService.buildForegroundNotification(
            active = !dormant,
            status = text,
        )?.let { return it }

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
        val builder = NotificationCompat.Builder(this, AssistantNotificationContract.CHANNEL_ID)
            .setContentTitle("枢卫 Main")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(actionIcon, actionText, controlPi)
            .setOngoing(true)

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(AssistantNotificationContract.NOTIFICATION_ID, buildNotification(text))
        MainMediaLibraryService.publishState(this, active = !dormant, status = text)
    }

}
