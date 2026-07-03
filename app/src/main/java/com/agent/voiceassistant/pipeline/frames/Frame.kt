package com.agent.voiceassistant.pipeline.frames

/**
 * 帧方向。DOWNSTREAM 沿管线正向传播（输入→输出），UPSTREAM 反向传播（如打断）。
 */
enum class FrameDirection { UPSTREAM, DOWNSTREAM }

/**
 * Frame：管线中传递的最小数据单元。
 * 参考 Pipecat frames.py，简化为 ~15 种核心帧。
 *
 * - [SystemFrame] 系统帧：高优先级，控制流（启停、打断、轮次事件），不可被普通帧打断
 * - [DataFrame]  数据帧：实际负载（音频、文本、函数结果），可被打断
 */
sealed interface Frame {
    val timestamp: Long

    /**
     * 是否需要立即处理（系统帧默认 true，数据帧默认 false）。
     * 用于 FrameProcessor 区分是否要插队。
     */
    val urgent: Boolean get() = this is SystemFrame
}

/**
 * 系统帧：控制信号。
 * data object 用法对应单例事件（无负载）；data class 用于带负载事件。
 */
sealed class SystemFrame : Frame {
    /** 管线启动，所有 processor 收到后做初始化 */
    data object StartFrame : SystemFrame() {
        override val timestamp: Long = System.nanoTime()
    }

    /** 管线停止，所有 processor 收到后释放资源 */
    data object EndFrame : SystemFrame() {
        override val timestamp: Long = System.nanoTime()
    }

    /** 取消当前轮次（不发结束事件，仅清状态） */
    data object CancelFrame : SystemFrame() {
        override val timestamp: Long = System.nanoTime()
    }

    /** 用户打断 Bot：立即停止 TTS/AudioTrack 并清空下游队列 */
    data class InterruptionFrame(
        override val timestamp: Long = System.nanoTime()
    ) : SystemFrame()

    /** 用户开始说话（VAD 检测到语音起始） */
    data object UserStartedSpeakingFrame : SystemFrame() {
        override val timestamp: Long = System.nanoTime()
    }

    /** 用户停止说话（VAD 检测到静音） */
    data object UserStoppedSpeakingFrame : SystemFrame() {
        override val timestamp: Long = System.nanoTime()
    }

    /** Bot 开始说话（TTS 第一段音频发出） */
    data object BotStartedSpeakingFrame : SystemFrame() {
        override val timestamp: Long = System.nanoTime()
    }

    /** Bot 停止说话（TTS 末段音频播完） */
    data object BotStoppedSpeakingFrame : SystemFrame() {
        override val timestamp: Long = System.nanoTime()
    }

    /** 用户空闲超过阈值（用于闲时汇报触发） */
    data class UserIdleFrame(
        val idleSeconds: Long,
        override val timestamp: Long = System.nanoTime()
    ) : SystemFrame()
}

/**
 * 数据帧：实际负载。
 * 注意 [InputAudioRawFrame] 与 [OutputAudioRawFrame] 内 ByteArray 是引用共享，
 * processor 不应原地修改，需要变换时拷贝新数组。
 */
sealed class DataFrame : Frame {
    /** 录音原始 PCM（16kHz 单声道 16bit，short 数组转 float 后存） */
    data class InputAudioRawFrame(
        val samples: FloatArray,        // 归一化到 [-1, 1] 的 PCM
        val sampleRate: Int,
        override val timestamp: Long = System.nanoTime()
    ) : DataFrame()

    /**
     * TTS 生成的 PCM，用于 AudioTrack 播放。
     * @param endOfUtterance 为此轮 TTS 的最后一个 chunk，AudioOutputProcessor 收到后会设置 Marker，
     *                      播放完成后自动推 [SystemFrame.BotStoppedSpeakingFrame]。
     */
    data class OutputAudioRawFrame(
        val samples: FloatArray,
        val sampleRate: Int,
        val endOfUtterance: Boolean = false,
        override val timestamp: Long = System.nanoTime()
    ) : DataFrame()

    /** 纯文本帧（系统注入用，例如汇报 prompt） */
    data class TextFrame(
        val text: String,
        override val timestamp: Long = System.nanoTime()
    ) : DataFrame()

    /** LLM 流式输出文本帧（增量片段） */
    data class LLMTextFrame(
        val text: String,
        override val timestamp: Long = System.nanoTime()
    ) : DataFrame()

    /** 待 TTS 的完整句子（句子聚合后产生） */
    data class TTSTextFrame(
        val text: String,
        override val timestamp: Long = System.nanoTime()
    ) : DataFrame()

    /** ASR 识别结果 */
    data class TranscriptionFrame(
        val text: String,
        val isFinal: Boolean,
        override val timestamp: Long = System.nanoTime()
    ) : DataFrame()

    /** 工具调用结果（注入回 LLM 上下文） */
    data class FunctionCallResultFrame(
        val result: String,
        val toolName: String,
        override val timestamp: Long = System.nanoTime()
    ) : DataFrame()

    /** LLM 完整响应结束信号（用于状态机判断一轮对话结束） */
    data class LLMResponseEndFrame(
        val fullText: String,
        override val timestamp: Long = System.nanoTime()
    ) : DataFrame()
}

/**
 * 工具函数：判断是否为可中断的数据帧。
 */
fun Frame.isInterruptible(): Boolean = this is DataFrame

/**
 * 工具函数：判断是否为打断/取消类系统帧。
 */
fun Frame.isInterruption(): Boolean =
    this is SystemFrame.InterruptionFrame || this is SystemFrame.CancelFrame
