package com.agent.voiceassistant.pipeline.processors

import android.content.res.AssetManager
import com.agent.voiceassistant.model.ModelPaths
import com.agent.voiceassistant.pipeline.FrameProcessor
import com.agent.voiceassistant.pipeline.frames.DataFrame
import com.agent.voiceassistant.pipeline.frames.Frame
import com.agent.voiceassistant.pipeline.frames.FrameDirection
import com.agent.voiceassistant.pipeline.frames.SystemFrame
import timber.log.Timber

/**
 * Legacy local TTS placeholder.
 *
 * The minimum cloud loop uses CloudSpeechClient directly. This class is kept so
 * the old pipeline package still compiles without bundling sherpa-onnx.
 */
@Suppress("UNUSED_PARAMETER")
class TTSProcessor(
    private val paths: ModelPaths,
    private val assetManager: AssetManager? = null,
    private val speakerId: Int = 0,
    private val speed: Float = 1.0f,
) : FrameProcessor() {

    override suspend fun processFrame(frame: Frame, direction: FrameDirection) {
        when (frame) {
            is DataFrame.TTSTextFrame -> Timber.w("Local TTS is disabled in cloud build: ${frame.text}")
            is DataFrame.LLMResponseEndFrame -> Unit
            is SystemFrame -> Unit
            else -> pushFrame(frame, direction)
        }
    }
}
