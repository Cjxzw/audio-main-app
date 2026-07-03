package com.agent.voiceassistant.pipeline.processors

import android.content.res.AssetManager
import com.agent.voiceassistant.model.ModelPaths
import com.agent.voiceassistant.pipeline.FrameProcessor
import com.agent.voiceassistant.pipeline.frames.Frame
import com.agent.voiceassistant.pipeline.frames.FrameDirection
import com.agent.voiceassistant.pipeline.frames.SystemFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Legacy local VAD placeholder.
 *
 * The minimum cloud loop uses SimpleVadRecorder directly. This class is kept so
 * the old pipeline package still compiles without bundling sherpa-onnx.
 */
@Suppress("UNUSED_PARAMETER")
class VADProcessor(
    private val paths: ModelPaths,
    private val assetManager: AssetManager? = null,
    private val threshold: Float = 0.5f,
    private val minSilenceDurationSec: Float = 0.4f,
    private val minSpeechDurationSec: Float = 0.25f,
    private val maxSpeechDurationSec: Float = 20.0f,
) : FrameProcessor() {

    private val _userSpeaking = MutableStateFlow(false)
    val userSpeaking = _userSpeaking.asStateFlow()

    override suspend fun processFrame(frame: Frame, direction: FrameDirection) {
        when (frame) {
            is SystemFrame.StartFrame -> Timber.i("Local VAD is disabled in cloud build")
            is SystemFrame.EndFrame, is SystemFrame.CancelFrame -> _userSpeaking.value = false
            is SystemFrame -> Unit
            else -> pushFrame(frame, direction)
        }
    }
}
