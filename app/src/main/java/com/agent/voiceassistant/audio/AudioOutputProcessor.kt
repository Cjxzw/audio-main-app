package com.agent.voiceassistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.agent.voiceassistant.pipeline.FrameProcessor
import com.agent.voiceassistant.pipeline.frames.DataFrame
import com.agent.voiceassistant.pipeline.frames.Frame
import com.agent.voiceassistant.pipeline.frames.FrameDirection
import com.agent.voiceassistant.pipeline.frames.SystemFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AudioOutputProcessor : FrameProcessor() {

    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = AudioConfig.DEFAULT_TTS_SAMPLE_RATE
    private val playing = AtomicBoolean(false)
    private val interrupted = AtomicBoolean(false)
    private val utteranceSampleCount = AtomicInteger(0)
    private var currentDelayJob: Job? = null

    override suspend fun processFrame(frame: Frame, direction: FrameDirection) {
        when (frame) {
            is DataFrame.OutputAudioRawFrame -> playSamples(frame)
            is SystemFrame.StartFrame -> {
                Timber.d("AudioOutput: start")
            }
            is SystemFrame.EndFrame, is SystemFrame.CancelFrame -> stopAll()
            is SystemFrame.InterruptionFrame -> {
                Timber.i("AudioOutput: interrupted, stopping playback")
                interrupted.set(true)
                stopPlayback()
            }
            else -> Unit
        }
    }

    private suspend fun playSamples(frame: DataFrame.OutputAudioRawFrame) {
        if (interrupted.get()) {
            Timber.d("AudioOutput: interrupted, drop ${frame.samples.size} samples")
            return
        }

        if (frame.endOfUtterance && frame.samples.isEmpty()) {
            scheduleStopAfterBufferedAudio()
            return
        }

        val track = ensureTrack(frame.sampleRate)

        if (!playing.get()) {
            playing.set(true)
            utteranceSampleCount.set(0)
            pushFrame(SystemFrame.BotStartedSpeakingFrame, FrameDirection.UPSTREAM)
            Timber.i("AudioOutput: >>> BotStartedSpeaking EMITTED, sampleRate=${frame.sampleRate}")
        }

        val shorts = AudioConfig.floatToShort(frame.samples)
        track.write(shorts, 0, shorts.size)
        utteranceSampleCount.addAndGet(shorts.size)

        if (frame.endOfUtterance) {
            scheduleStopAfterBufferedAudio()
        }
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack {
        audioTrack?.let { existing ->
            if (currentSampleRate == sampleRate) return existing
            try { existing.stop() } catch (_: Exception) {}
            existing.release()
        }
        val bufferSize = (sampleRate * AudioConfig.OUTPUT_BUFFER_FACTOR / 50).coerceAtLeast(
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioConfig.INPUT_AUDIO_FORMAT
            )
        )
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioConfig.INPUT_AUDIO_FORMAT)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.play()
        currentSampleRate = sampleRate
        audioTrack = track
        Timber.i("AudioTrack created, sampleRate=$sampleRate, bufSize=$bufferSize")
        return track
    }

    private fun scheduleStopAfterBufferedAudio() {
        if (!playing.get()) {
            Timber.d("AudioOutput: endOfUtterance while not playing")
            return
        }
        val totalSamples = utteranceSampleCount.get()
        val playbackHead = audioTrack?.playbackHeadPosition ?: 0
        val remainingSamples = (totalSamples - playbackHead).coerceAtLeast(0)
        val durationMs = (remainingSamples * 1000L) / currentSampleRate

        currentDelayJob?.cancel()
        currentDelayJob = processorScope?.launch {
            delay(durationMs + 250)
            if (playing.get()) {
                stopPlayback()
            }
        }
        Timber.i("AudioOutput: endOfUtterance, totalSamples=$totalSamples, played=$playbackHead, remaining=$remainingSamples, durationMs=$durationMs, job=${currentDelayJob?.isActive}")
    }

    private suspend fun stopPlayback() {
        if (!playing.get()) return
        playing.set(false)
        interrupted.set(false)
        currentDelayJob?.cancel()
        currentDelayJob = null
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
            } catch (_: Exception) {}
        }
        pushFrame(SystemFrame.BotStoppedSpeakingFrame, FrameDirection.UPSTREAM)
        Timber.i("AudioOutput: >>> BotStoppedSpeaking EMITTED")
    }

    private suspend fun stopAll() {
        stopPlayback()
        audioTrack?.release()
        audioTrack = null
        interrupted.set(false)
    }

    override suspend fun cleanup() {
        super.cleanup()
        interrupted.set(true)
        playing.set(false)
        currentDelayJob?.cancel()
        currentDelayJob = null
        audioTrack?.let { track ->
            try { track.pause(); track.flush() } catch (_: Exception) {}
        }
        interrupted.set(false)
    }
}
