package com.agent.voiceassistant.report

import com.agent.voiceassistant.pipeline.Pipeline
import com.agent.voiceassistant.pipeline.frames.DataFrame
import com.agent.voiceassistant.pipeline.frames.FrameDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.PriorityBlockingQueue

/**
 * PendingResultReporter：闲时择机汇报策略层。
 *
 * 设计（参考 GLaDOS 双通道 + Cooldown + Coalesce）：
 * - [Priority Lane]：用户语音输入优先处理（由 VAD 触发 [SystemFrame.UserStartedSpeakingFrame]）
 * - [Autonomy Lane]：后台结果汇报，可被用户输入打断
 * - [Cooldown]：两次自主汇报之间最小间隔（默认 20s）
 * - [Coalesce]：多个结果合并汇报（队列空之前一次性处理）
 *
 * 状态机：
 * - 用户空闲 → 检查队列 → 注入 LLM → 等待 Bot 说完 → 下一条
 * - 用户开始说话 → 暂停汇报（紧急除外）
 * - 紧急结果 → 打断 Bot，立即汇报
 */
class PendingResultReporter(
    private val pipeline: Pipeline,
    /** 最小汇报间隔（毫秒） */
    private val cooldownMs: Long = 20_000L,
    /** 检查周期（毫秒） */
    private val checkIntervalMs: Long = 1_000L
) {
    /** 优先级队列（按 [Priority.weight] 排序） */
    private val resultQueue = PriorityBlockingQueue<PendingResult>(
        11,
        compareBy { it.priority.weight }
    )

    /** 用户是否空闲 */
    @Volatile private var userIdle = true
    /** Bot 是否正在播报 */
    @Volatile private var botSpeaking = false
    /** 正在汇报中 */
    @Volatile private var reporting = false
    /** 上次汇报时间 */
    @Volatile private var lastReportTime: Long = 0L

    private val _pendingCount = MutableStateFlow(0)
    /** 待汇报队列大小（供 UI 显示） */
    val pendingCount = _pendingCount.asStateFlow()

    private var scope: CoroutineScope? = null
    private var idleJob: Job? = null

    /** 启动汇报调度循环 */
    fun start(scope: CoroutineScope) {
        if (idleJob?.isActive == true) return
        this.scope = scope
        idleJob = scope.launch(Dispatchers.Default) {
            Timber.i("PendingResultReporter started, cooldown=${cooldownMs}ms")
            while (true) {
                try {
                    tryDrain()
                } catch (e: Exception) {
                    Timber.e(e, "Reporter drain failed")
                }
                delay(checkIntervalMs)
            }
        }
    }

    /** 停止汇报调度 */
    fun stop() {
        idleJob?.cancel()
        idleJob = null
        scope = null
    }

    /**
     * 外部入队：Worker 完成后调用。
     */
    fun enqueueResult(result: PendingResult) {
        resultQueue.put(result)
        _pendingCount.value = resultQueue.size
        Timber.i("Reporter: enqueued ${result.taskId} (priority=${result.priority}, queue=${resultQueue.size})")
    }

    /** 用户开始说话：暂停汇报（紧急除外） */
    fun onUserStartedSpeaking() {
        userIdle = false
        Timber.d("Reporter: user started speaking, pause reporting")
    }

    /** 用户停止说话：可恢复汇报 */
    fun onUserStoppedSpeaking() {
        // 等待一段空闲后才认为真的空闲
        scope?.launch {
            delay(2_000)  // 2 秒静默
            userIdle = true
            Timber.d("Reporter: user idle, resume reporting")
        }
    }

    /** Bot 开始播报 */
    fun onBotStartedSpeaking() {
        botSpeaking = true
    }

    /** Bot 停止播报 */
    fun onBotStoppedSpeaking() {
        botSpeaking = false
    }

    /** 立即触发紧急汇报（打断当前 Bot 播报） */
    suspend fun triggerUrgent(result: PendingResult) {
        Timber.w("Reporter: URGENT report triggered, interrupting bot")
        pipeline.sendUpstream(com.agent.voiceassistant.pipeline.frames.SystemFrame.InterruptionFrame())
        delay(300)  // 给管线一点时间停止 TTS
        injectResult(result)
    }

    private suspend fun tryDrain() {
        if (resultQueue.isEmpty()) return

        val head = resultQueue.peek() ?: return

        // 紧急结果立即处理（可打断）
        if (head.priority == Priority.URGENT) {
            resultQueue.poll()
            _pendingCount.value = resultQueue.size
            triggerUrgent(head)
            return
        }

        // 普通结果：等用户空闲 + bot 静默 + cooldown
        if (reporting) return
        if (botSpeaking) return
        if (!userIdle) return
        if (System.currentTimeMillis() - lastReportTime < cooldownMs) return

        reporting = true
        try {
            // Coalesce：把队列里所有非紧急结果合并汇报（限 3 条避免一次说太多）
            val batch = mutableListOf<PendingResult>()
            while (resultQueue.isNotEmpty() && batch.size < 3) {
                val r = resultQueue.poll() ?: break
                if (r.priority == Priority.URGENT) {
                    // 紧急的放回，下一轮处理
                    resultQueue.put(r)
                    break
                }
                batch.add(r)
            }
            _pendingCount.value = resultQueue.size

            if (batch.isEmpty()) return

            val combined = batch.joinToString("\n") { r ->
                "- ${r.taskType}任务：${r.summary}"
            }
            val prompt = "[系统通知] 以下后台任务已完成：\n$combined\n请用简洁的语音向用户汇报结果。"
            Timber.i("Reporter: injecting ${batch.size} results")

            // 注入到 LLM 上下文（通过 TextFrame 上游推送）
            pipeline.sendUpstream(DataFrame.TextFrame(prompt))

            // 等待 Bot 播报完成（最多 30 秒超时）
            val startWait = System.currentTimeMillis()
            while (botSpeaking && System.currentTimeMillis() - startWait < 30_000) {
                delay(200)
            }

            // 标记已汇报
            batch.forEach { it.reported = true; it.reportedAt = System.currentTimeMillis() }
            lastReportTime = System.currentTimeMillis()
            Timber.i("Reporter: batch reported, ${batch.size} results")
        } finally {
            reporting = false
        }
    }

    private suspend fun injectResult(result: PendingResult) {
        val prompt = "[系统通知] 紧急任务 ${result.taskId} 完成：\n${result.summary}\n请立即用简洁的语音向用户汇报。"
        pipeline.sendUpstream(DataFrame.TextFrame(prompt))
        result.reported = true
        result.reportedAt = System.currentTimeMillis()
        lastReportTime = System.currentTimeMillis()
    }
}
