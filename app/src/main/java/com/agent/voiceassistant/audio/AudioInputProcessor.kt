package com.agent.voiceassistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.agent.voiceassistant.pipeline.FrameProcessor
import com.agent.voiceassistant.pipeline.frames.DataFrame
import com.agent.voiceassistant.pipeline.frames.Frame
import com.agent.voiceassistant.pipeline.frames.FrameDirection
import com.agent.voiceassistant.pipeline.frames.SystemFrame
import com.agent.voiceassistant.service.EventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioInputProcessor : FrameProcessor() {

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val running = AtomicBoolean(false)

    private val framesPushed = AtomicLong(0)
    private val readErrors = AtomicLong(0)

    private var lastVolumeEmit = 0L

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun ensureRecorder(): AudioRecord? {
        audioRecord?.let { return it }
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        )
        for (source in sources) {
            val recorder = try {
                AudioRecord(
                    source,
                    AudioConfig.INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioConfig.INPUT_AUDIO_FORMAT,
                    AudioConfig.INPUT_BUFFER_SIZE,
                )
            } catch (e: Exception) {
                Timber.w(e, "AudioRecord init failed for source=$source")
                continue
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Timber.w("AudioRecord not initialized, source=$source state=${recorder.state}")
                recorder.release()
                continue
            }
            audioRecord = recorder
            Timber.i("AudioRecord initialized with source=$source (state=${recorder.state})")
            return recorder
        }
        Timber.e("All audio sources failed")
        return null
    }

    override suspend fun processFrame(frame: Frame, direction: FrameDirection) {
        when (frame) {
            is SystemFrame.StartFrame -> startRecording()
            is SystemFrame.EndFrame, is SystemFrame.CancelFrame -> stopRecording()
            is SystemFrame.InterruptionFrame -> {
                Timber.d("AudioInput: interruption, keep recording")
            }
            else -> {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (running.get()) {
            Timber.w("AudioInput: startRecording called but already running")
            return
        }
        val recorder = ensureRecorder() ?: run {
            Timber.e("Cannot start recording: recorder null")
            return
        }
        val scope = processorScope ?: run {
            Timber.e("Cannot start recording: scope null (FrameProcessor.start not called?)")
            return
        }
        running.set(true)
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Timber.e(e, "AudioRecord.startRecording() threw")
            running.set(false)
            return
        }
        Timber.i("AudioInput started, source=${recorder.audioSource}, bufSize=${AudioConfig.INPUT_BUFFER_SIZE}, vadFrame=${AudioConfig.VAD_FRAME_SAMPLES}")

        recordJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(AudioConfig.VAD_FRAME_SAMPLES)
            var lastReportTime = System.currentTimeMillis()
            var lastReportFrames = 0L
            var energyAccum = 0.0
            var energyCount = 0

            while (running.get()) {
                val read = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    Timber.e(e, "AudioRecord.read failed")
                    readErrors.incrementAndGet()
                    break
                }
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                        Timber.e("AudioRecord.read returned ERROR_INVALID_OPERATION")
                        break
                    } else if (read == AudioRecord.ERROR_BAD_VALUE) {
                        Timber.e("AudioRecord.read returned ERROR_BAD_VALUE")
                        break
                    }
                    continue
                }
                var sumSq = 0.0
                var peak = 0
                for (i in 0 until read) {
                    val raw = buffer[i].toInt()
                    if (Math.abs(raw) > peak) peak = Math.abs(raw)
                    val v = raw / 32768.0
                    sumSq += v * v
                }
                energyAccum += sumSq / read
                energyCount++
                val frameRms = Math.sqrt(sumSq / read).toFloat()

                val samples = AudioConfig.shortToFloat(
                    if (read == buffer.size) buffer else buffer.copyOf(read)
                )
                val audioFrame = DataFrame.InputAudioRawFrame(
                    samples = samples,
                    sampleRate = AudioConfig.INPUT_SAMPLE_RATE
                )
                pushFrame(audioFrame, FrameDirection.DOWNSTREAM)
                framesPushed.incrementAndGet()

                val now = System.currentTimeMillis()
                if (now - lastVolumeEmit >= 100) {
                    val avgEnergy = if (energyCount > 0) energyAccum / energyCount else 0.0
                    val rms = Math.sqrt(avgEnergy).toFloat()
                    EventBus.emitVolume(rms)
                    lastVolumeEmit = now
                }

                if (now - lastReportTime >= 1000) {
                    val fps = framesPushed.get() - lastReportFrames
                    val avgEnergy = if (energyCount > 0) energyAccum / energyCount else 0.0
                    val rms = Math.sqrt(avgEnergy)
                    Timber.i("AudioInput stats: fps=$fps, totalFrames=${framesPushed.get()}, rms=${"%.4f".format(rms)} (raw=${"%.0f".format(rms * 32768)}), peak=$peak, readErrors=${readErrors.get()}")
                    lastReportTime = now
                    lastReportFrames = framesPushed.get()
                    energyAccum = 0.0
                    energyCount = 0
                }
            }
            Timber.w("AudioInput recording loop exited, running=${running.get()}")
        }
    }

    private fun stopRecording() {
        running.set(false)
        recordJob?.cancel()
        recordJob = null
        audioRecord?.let {
            try { it.stop() } catch (e: Exception) {
                Timber.w(e, "AudioRecord.stop() threw")
            }
            it.release()
        }
        audioRecord = null
        EventBus.emitVolume(0f)
        Timber.i("AudioInput stopped (totalFrames=${framesPushed.get()}, totalErrors=${readErrors.get()})")
    }

    override suspend fun cleanup() {
        super.cleanup()
    }
}
