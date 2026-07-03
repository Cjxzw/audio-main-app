package com.agent.voiceassistant.audio

/**
 * 全局音频参数常量。
 *
 * 关键约束：
 * - VAD/ASR 要求 16kHz 单声道 16bit PCM
 * - TTS 输出采样率由模型决定（Piper zh_CN-huayan-medium 输出 22050Hz），需 AudioTrack 动态适配
 *
 * 因此 AudioRecord 固定 16kHz；AudioTrack 按 TTS 实际输出采样率创建。
 */
object AudioConfig {
    /** 输入采样率（Hz）- VAD/ASR/Paraformer 要求 */
    const val INPUT_SAMPLE_RATE = 16_000

    /** 输入声道 - 单声道 */
    const val INPUT_CHANNELS = 1

    /** 输入编码 - 16bit PCM */
    const val INPUT_AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT

    /** 录音缓冲区大小（字节），按最小值 2 倍取以保证平滑 */
    val INPUT_BUFFER_SIZE: Int = run {
        val min = android.media.AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            INPUT_AUDIO_FORMAT
        )
        if (min <= 0) INPUT_SAMPLE_RATE * 2 else min * 2
    }

    /** VAD 单次处理帧大小（samples）- 32ms @ 16kHz */
    const val VAD_FRAME_SAMPLES = 512

    /** ASR 流式分片大小（samples）- 100ms @ 16kHz */
    const val ASR_CHUNK_SAMPLES = 1_600

    /** 默认 TTS 输出采样率（Piper zh_CN-huayan-medium 实测） */
    const val DEFAULT_TTS_SAMPLE_RATE = 22_050

    /** AudioTrack 缓冲区大小因子 */
    const val OUTPUT_BUFFER_FACTOR = 4

    /** 输入音频归一化：16bit PCM [-32768, 32767] -> float [-1, 1] */
    const val PCM_16BIT_MAX = 32_768.0f

    /** 将 short 数组转 float（归一化） */
    fun shortToFloat(samples: ShortArray): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = samples[i] / PCM_16BIT_MAX
        }
        return out
    }

    /** 将 float（[-1, 1]）转 short */
    fun floatToShort(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = (samples[i] * PCM_16BIT_MAX).toInt()
            out[i] = v.coerceIn(-32_768, 32_767).toShort()
        }
        return out
    }
}
