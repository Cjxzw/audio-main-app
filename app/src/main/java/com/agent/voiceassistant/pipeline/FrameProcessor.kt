package com.agent.voiceassistant.pipeline

import com.agent.voiceassistant.pipeline.frames.Frame
import com.agent.voiceassistant.pipeline.frames.FrameDirection
import com.agent.voiceassistant.pipeline.frames.SystemFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FrameProcessor：管线中的处理节点基类。
 *
 * 设计（参考 Pipecat frame_processor.py）：
 * - 双向链表：[nextProc] / [prevProc]
 * - 输入队列：[inputQueue] 缓冲上游推送过来的帧
 * - 优先级：[SystemFrame] 走 fast-path（直接处理），[DataFrame] 走队列
 * - **SystemFrame 自动转发**：fast-path 处理完后自动 pushFrame 到同方向下游/上游
 *   确保 StartFrame/EndFrame/InterruptionFrame 能传遍整条链
 *
 * 外部 API：
 * - [input] 把帧塞进队列（数据帧）或 fast-path（系统帧）
 * - [pushFrame] 子类在 processFrame 内转发到上下游（手动方向控制）
 */
abstract class FrameProcessor {

    @Volatile internal var nextProc: FrameProcessor? = null
    @Volatile internal var prevProc: FrameProcessor? = null

    /** 数据帧缓冲（无界，应对 LLM 流式突发） */
    internal val inputQueue = Channel<Frame>(Channel.UNLIMITED)

    private val started = AtomicBoolean(false)
    private var pumpJob: Job? = null

    /**
     * 子类可访问的协程作用域（在 [start] 后非空）。
     * 用于启动子任务（如 AudioInputProcessor 的录音读取循环）。
     */
    protected var processorScope: CoroutineScope? = null
        private set

    /** 子类实现：处理一帧。 */
    protected abstract suspend fun processFrame(frame: Frame, direction: FrameDirection)

    /**
     * 启动消费者循环。由 Pipeline 统一调用。
     */
    internal fun start(scope: CoroutineScope) {
        if (pumpJob?.isActive == true) return
        processorScope = scope
        pumpJob = scope.launch(Dispatchers.Default) {
            try {
                for (frame in inputQueue) {
                    if (!isActive) break
                    try {
                        processFrame(frame, FrameDirection.DOWNSTREAM)
                    } catch (e: Exception) {
                        Timber.e(e, "${this@FrameProcessor::class.simpleName} processFrame failed")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "${this@FrameProcessor::class.simpleName} pump loop crashed")
            }
        }
    }

    /** 停止消费者循环。 */
    internal fun stop() {
        pumpJob?.cancel()
        pumpJob = null
        processorScope = null
        inputQueue.close()
    }

    /**
     * 外部入队接口。
     *
     * - [SystemFrame] 直接 fast-path 调用 [processFrame]，**处理完后自动转发给同方向下一个节点**
     *   确保 StartFrame/EndFrame/InterruptionFrame 能传遍整条链
     * - [DataFrame] 进入 [inputQueue]
     *
     * @param direction 帧的传播方向，决定自动转发的目标
     */
    suspend fun input(frame: Frame, direction: FrameDirection = FrameDirection.DOWNSTREAM) {
        if (frame is SystemFrame && frame !is SystemFrame.UserIdleFrame) {
            // 系统帧立即处理
            try {
                processFrame(frame, direction)
            } catch (e: Exception) {
                Timber.e(e, "${this::class.simpleName} input(system) failed")
            }
            // 自动转发系统帧到同方向（确保 StartFrame 等能传遍整条链）
            pushFrame(frame, direction)
        } else {
            if (!inputQueue.isClosedForSend) {
                inputQueue.send(frame)
            }
        }
    }

    /**
     * 推送帧到下游/上游。子类在 processFrame 中调用以转发。
     * 注意：对于 SystemFrame，会自动按 direction 链式转发；
     * 对于 DataFrame，只会送到直接下游/上游一个节点（由其 input 决定后续）。
     */
    suspend fun pushFrame(frame: Frame, direction: FrameDirection = FrameDirection.DOWNSTREAM) {
        val target = if (direction == FrameDirection.DOWNSTREAM) nextProc else prevProc
        target?.input(frame, direction)
    }

    /**
     * cleanup：打断/取消时清空输入队列。
     * 子类重写时调用 super.cleanup() 后扩展。
     */
    open suspend fun cleanup() {
        while (true) {
            val f = inputQueue.tryReceive().getOrNull() ?: break
            Timber.d("${this::class.simpleName} cleanup dropped: ${f::class.simpleName}")
        }
    }

    /** Pipeline 内部链接上下游 */
    internal fun linkNext(n: FrameProcessor?) { nextProc = n }
    internal fun linkPrev(p: FrameProcessor?) { prevProc = p }
}
