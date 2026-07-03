package com.agent.voiceassistant.pipeline

import com.agent.voiceassistant.pipeline.frames.Frame
import com.agent.voiceassistant.pipeline.frames.FrameDirection
import com.agent.voiceassistant.pipeline.frames.SystemFrame
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

/**
 * Pipeline：链式连接若干 FrameProcessor。
 *
 * 链式结构：[A] <-> [B] <-> [C]
 * - A.pushFrame(DOWNSTREAM) -> B.input(frame) -> B.processFrame
 * - C.pushFrame(UPSTREAM)   -> B.input(frame) -> B.processFrame
 *
 * 用法：
 * ```
 * val pipeline = Pipeline(audioIn, vad, asr, llm, tts, audioOut)
 * pipeline.start(scope)
 * pipeline.send(SystemFrame.StartFrame)
 * ...
 * pipeline.send(SystemFrame.EndFrame)
 * pipeline.stop()
 * ```
 */
class Pipeline(vararg processors: FrameProcessor) {

    private val list: List<FrameProcessor> = processors.toList()
    private var started = false

    init {
        require(processors.isNotEmpty()) { "Pipeline must have at least one processor" }
        for (i in processors.indices) {
            if (i + 1 < processors.size) {
                processors[i].linkNext(processors[i + 1])
                processors[i + 1].linkPrev(processors[i])
            }
        }
    }

    /** 所有 processor 启动消费者循环 */
    fun start(scope: CoroutineScope) {
        if (started) return
        list.forEach { it.start(scope) }
        started = true
        Timber.i("Pipeline started with ${list.size} processors")
    }

    /** 停止所有 processor */
    fun stop() {
        if (!started) return
        list.forEach { it.stop() }
        started = false
        Timber.i("Pipeline stopped")
    }

    /**
     * 把帧送进首节点。系统帧走 fast-path，数据帧进队列。
     */
    suspend fun send(frame: Frame) {
        list.firstOrNull()?.input(frame)
    }

    /**
     * 把帧送到尾节点，反向传播（UPSTREAM）。
     * 例如汇报策略层把 TextFrame 从尾端注入，让 LLMProcessor 处理。
     */
    suspend fun sendUpstream(frame: Frame) {
        list.lastOrNull()?.input(frame, FrameDirection.UPSTREAM)
    }

    /**
     * 触发所有 processor 的 cleanup（用户打断时调用）。
     * 顺序：从打断发生点向下游扩散。
     */
    suspend fun cleanup() {
        list.forEach { processor ->
            try {
                processor.cleanup()
            } catch (e: Exception) {
                Timber.e(e, "Pipeline cleanup failed at ${processor::class.simpleName}")
            }
        }
    }

    /** processor 数量 */
    fun size(): Int = list.size

    /** 取第 index 个 processor */
    operator fun get(index: Int): FrameProcessor = list[index]

    /** 取首个 processor（通常为输入端） */
    val first: FrameProcessor? get() = list.firstOrNull()

    /** 取末尾 processor（通常为输出端） */
    val last: FrameProcessor? get() = list.lastOrNull()
}
