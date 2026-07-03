package com.agent.voiceassistant.cloud

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import com.agent.voiceassistant.audio.AudioRouteManager
import com.agent.voiceassistant.service.EventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

class SimpleVadRecorder(
    private val routeManager: AudioRouteManager? = null,
) {
    data class Recording(
        val wavBytes: ByteArray,
        val durationMs: Long,
    )

    private val stopped = AtomicBoolean(false)

    fun stop() {
        stopped.set(true)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun recordNextUtterance(): Recording? = withContext(Dispatchers.IO) {
        stopped.set(false)
        val record = createAudioRecord()
        val frames = ArrayList<ShortArray>(256)
        val preRoll = ArrayDeque<ShortArray>()
        var speechStarted = false
        var startFrames = 0
        var silenceFrames = 0
        var capturedSamples = 0
        var smoothedRms = 0f
        var noiseSum = 0f
        var noiseFrames = 0
        var noiseFloor = START_RMS_BASE
        var startThreshold = START_RMS_BASE
        var stopThreshold = STOP_RMS_BASE
        var lastVolumeEmitMs = 0L

        try {
            routeManager?.applyInputRouting(record)
            record.startRecording()
            Timber.i("CloudRecorder: started, source=${record.audioSource}, buffer=${record.bufferSizeInFrames} frames")
            routeManager?.logRecordRoute(record, "started")

            val buffer = ShortArray(FRAME_SAMPLES)
            while (!stopped.get()) {
                coroutineContext.ensureActive()
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                val frame = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                val rms = computeRms(frame, read)
                smoothedRms += SMOOTH_FACTOR * (rms - smoothedRms)

                if (!speechStarted && noiseFrames < CALIBRATION_FRAMES) {
                    noiseSum += rms
                    noiseFrames++
                    noiseFloor = noiseSum / noiseFrames
                    startThreshold = max(START_RMS_BASE, noiseFloor * START_NOISE_MULTIPLIER)
                    stopThreshold = max(STOP_RMS_BASE, noiseFloor * STOP_NOISE_MULTIPLIER)
                }

                val now = System.currentTimeMillis()
                if (now - lastVolumeEmitMs >= VOLUME_EMIT_INTERVAL_MS) {
                    val visibleLevel = if (noiseFrames >= CALIBRATION_FRAMES) {
                        ((smoothedRms - noiseFloor).coerceAtLeast(0f) * VOLUME_UI_GAIN)
                    } else {
                        smoothedRms * WARMUP_VOLUME_UI_GAIN
                    }
                    EventBus.emitVolume(visibleLevel.coerceIn(0f, 1f))
                    lastVolumeEmitMs = now
                }

                if (!speechStarted && noiseFrames < CALIBRATION_FRAMES) {
                    continue
                }

                if (!speechStarted) {
                    preRoll.addLast(frame)
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (smoothedRms >= startThreshold) {
                        startFrames++
                        if (startFrames >= START_FRAMES_REQUIRED) {
                            speechStarted = true
                            while (preRoll.isNotEmpty()) {
                                val pre = preRoll.removeFirst()
                                frames += pre
                                capturedSamples += pre.size
                            }
                            Timber.i(
                                "CloudRecorder: speech started, rms=${"%.5f".format(smoothedRms)}, " +
                                    "noise=${"%.5f".format(noiseFloor)}, start=${"%.5f".format(startThreshold)}"
                            )
                        }
                    } else {
                        startFrames = 0
                    }
                    continue
                }

                frames += frame
                capturedSamples += read

                if (smoothedRms < stopThreshold) {
                    silenceFrames++
                } else {
                    silenceFrames = 0
                }

                val capturedMs = capturedSamples * 1000L / SAMPLE_RATE
                if (capturedMs >= MIN_UTTERANCE_MS && silenceFrames >= END_SILENCE_FRAMES) {
                    trimTrailingSilence(frames, silenceFrames)
                    buildRecording(frames)?.let { return@withContext it }
                    frames.clear()
                    preRoll.clear()
                    speechStarted = false
                    startFrames = 0
                    silenceFrames = 0
                    capturedSamples = 0
                }
                if (capturedMs >= MAX_UTTERANCE_MS) {
                    Timber.i("CloudRecorder: max utterance reached (${capturedMs}ms)")
                    buildRecording(frames)?.let { return@withContext it }
                    frames.clear()
                    preRoll.clear()
                    speechStarted = false
                    startFrames = 0
                    silenceFrames = 0
                    capturedSamples = 0
                }
            }
            null
        } finally {
            runCatching { record.stop() }
            record.release()
            EventBus.emitVolume(0f)
            Timber.i("CloudRecorder: stopped")
        }
    }

    private fun trimTrailingSilence(frames: ArrayList<ShortArray>, silenceFrames: Int) {
        val drop = (silenceFrames - KEEP_TRAILING_SILENCE_FRAMES).coerceAtLeast(0)
        repeat(drop.coerceAtMost(frames.size)) {
            frames.removeAt(frames.lastIndex)
        }
    }

    private fun buildRecording(frames: List<ShortArray>): Recording? {
        val sampleCount = frames.sumOf { it.size }
        if (sampleCount < SAMPLE_RATE * MIN_UTTERANCE_MS / 1000) return null
        val pcm = ShortArray(sampleCount)
        var offset = 0
        for (frame in frames) {
            for (sample in frame) {
                pcm[offset++] = applyInputGain(sample)
            }
        }
        val durationMs = sampleCount * 1000L / SAMPLE_RATE
        val rms = computeRms(pcm, pcm.size)
        if (durationMs < MIN_VALID_UTTERANCE_MS || rms < MIN_VALID_RMS) {
            Timber.i(
                "CloudRecorder: reject noise segment, samples=$sampleCount, " +
                    "duration=${durationMs}ms, rms=${"%.5f".format(rms)}"
            )
            return null
        }
        Timber.i(
            "CloudRecorder: utterance complete, samples=$sampleCount, " +
                "duration=${durationMs}ms, rms=${"%.5f".format(rms)}"
        )
        return Recording(WavUtil.pcm16ToWav(pcm, SAMPLE_RATE), durationMs)
    }

    private fun applyInputGain(sample: Short): Short {
        val amplified = (sample * INPUT_GAIN).toInt()
        return amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun computeRms(samples: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            val normalized = samples[i] / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / max(size, 1)).toFloat()
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        routeManager?.ensureConfigured()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBuffer, FRAME_SAMPLES * 12 * 2)
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        for (source in audioSources()) {
            val record = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            }.getOrNull()
            if (record?.state == AudioRecord.STATE_INITIALIZED) {
                return record
            }
            record?.release()
        }
        error("AudioRecord 初始化失败")
    }

    private fun audioSources(): List<Int> = buildList {
        add(routeManager?.preferredAudioSource() ?: MediaRecorder.AudioSource.VOICE_RECOGNITION)
        add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        add(MediaRecorder.AudioSource.MIC)
        add(MediaRecorder.AudioSource.DEFAULT)
    }.distinct()

    private companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 512
        private const val SMOOTH_FACTOR = 0.25f
        private const val START_RMS_BASE = 0.0030f
        private const val STOP_RMS_BASE = 0.0014f
        private const val START_NOISE_MULTIPLIER = 4.0f
        private const val STOP_NOISE_MULTIPLIER = 2.0f
        private const val START_FRAMES_REQUIRED = 7
        private const val CALIBRATION_MS = 480L
        private const val VOLUME_UI_GAIN = 16f
        private const val WARMUP_VOLUME_UI_GAIN = 6f
        private const val INPUT_GAIN = 2.0f
        private const val MIN_UTTERANCE_MS = 350L
        private const val MIN_VALID_UTTERANCE_MS = 500L
        private const val MIN_VALID_RMS = 0.0025f
        private const val MAX_UTTERANCE_MS = 15_000L
        private const val END_SILENCE_MS = 750L
        private const val KEEP_TRAILING_SILENCE_MS = 180L
        private const val PRE_ROLL_MS = 450L
        private const val VOLUME_EMIT_INTERVAL_MS = 100L
        private const val FRAME_MS = FRAME_SAMPLES * 1000 / SAMPLE_RATE
        private const val CALIBRATION_FRAMES = CALIBRATION_MS.toInt() / FRAME_MS
        private const val END_SILENCE_FRAMES = END_SILENCE_MS.toInt() / FRAME_MS
        private const val KEEP_TRAILING_SILENCE_FRAMES = KEEP_TRAILING_SILENCE_MS.toInt() / FRAME_MS
        private const val PRE_ROLL_FRAMES = PRE_ROLL_MS.toInt() / FRAME_MS
    }
}
