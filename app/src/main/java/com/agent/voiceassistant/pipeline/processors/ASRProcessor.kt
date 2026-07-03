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
 * Legacy local ASR placeholder.
 *
 * The minimum cloud loop uses CloudSpeechClient directly. This class is kept so
 * the old pipeline package still compiles without bundling sherpa-onnx.
 */
@Suppress("UNUSED_PARAMETER")
class ASRProcessor(
    private val paths: ModelPaths,
    private val assetManager: AssetManager? = null,
) : FrameProcessor() {

    override suspend fun processFrame(frame: Frame, direction: FrameDirection) {
        when (frame) {
            is DataFrame.InputAudioRawFrame -> Timber.w("Local ASR is disabled in cloud build")
            is SystemFrame -> Unit
            else -> pushFrame(frame, direction)
        }
    }
}
