package com.agent.voiceassistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.sin

class EarconPlayer(
    private val routeManagerProvider: () -> AudioRouteManager?,
) {

    suspend fun listening() = playTone("listening", listOf(660.0, 880.0), 130, gain = 0.58)
    suspend fun captureDone() = playTone("capture_done", listOf(880.0, 660.0), 130, gain = 0.58)
    suspend fun waiting() = playTone("waiting", listOf(760.0, 760.0), 150, gain = 0.50)
    suspend fun reporting() = playTone("reporting", listOf(740.0, 932.0, 1108.0), 105, gain = 0.54)
    suspend fun preSleep() = playTone("pre_sleep", listOf(620.0, 520.0), 180, gain = 0.56)
    suspend fun sleep() = playTone("sleep", listOf(660.0, 440.0), 150, gain = 0.58)
    suspend fun playbackDone() = playTone(listOf(784.0, 988.0), 120, gain = 0.45)
    suspend fun error() = playTone("error", listOf(240.0, 180.0, 240.0), 180, gain = 0.70)

    private suspend fun playTone(
        frequencies: List<Double>,
        segmentMs: Int,
        gain: Double = 0.38,
    ) = playTone("feedback", frequencies, segmentMs, gain)

    private suspend fun playTone(
        label: String,
        frequencies: List<Double>,
        segmentMs: Int,
        gain: Double = 0.38,
    ) = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val pcm = buildPcm(frequencies, segmentMs, gain)
        val routes = routeManagerProvider()
        val createStartedAt = SystemClock.elapsedRealtime()
        val track = createTrack(pcm.size, communicationSession = routes?.communicationSession == true)
        val createElapsedMs = SystemClock.elapsedRealtime() - createStartedAt
        try {
            if (SystemClock.elapsedRealtime() - startedAt > MAX_START_LATENCY_MS) {
                Timber.w("Earcon: $label dropped after slow create elapsedMs=$createElapsedMs")
                return@withContext
            }
            routes?.applyOutputRouting(track)
            val written = track.write(pcm, 0, pcm.size)
            track.play()
            Timber.i(
                "Earcon: $label started bytes=$written session=${track.audioSessionId} " +
                    "domain=${if (routes?.communicationSession == true) "communication" else "media"} createMs=$createElapsedMs",
            )
            delay(frequencies.size * segmentMs + 80L)
            Timber.i("Earcon: $label finished totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        } catch (e: Exception) {
            Timber.w(e, "Earcon: $label playback failed")
        } finally {
            val releaseStartedAt = SystemClock.elapsedRealtime()
            runCatching { track.stop() }
            runCatching { track.release() }
            val releaseElapsedMs = SystemClock.elapsedRealtime() - releaseStartedAt
            if (releaseElapsedMs >= SLOW_RELEASE_WARNING_MS) {
                Timber.w("Earcon: $label slow release elapsedMs=$releaseElapsedMs")
            }
        }
    }

    private fun buildPcm(frequencies: List<Double>, segmentMs: Int, gain: Double): ByteArray {
        val totalSamples = SAMPLE_RATE * segmentMs * frequencies.size / 1000
        val bytes = ByteArray(totalSamples * 2)
        var byteIndex = 0
        for (freq in frequencies) {
            val samples = SAMPLE_RATE * segmentMs / 1000
            for (i in 0 until samples) {
                val envelope = when {
                    i < FADE_SAMPLES -> i.toDouble() / FADE_SAMPLES
                    i > samples - FADE_SAMPLES -> (samples - i).toDouble() / FADE_SAMPLES
                    else -> 1.0
                }.coerceIn(0.0, 1.0)
                val value = (sin(2.0 * PI * freq * i / SAMPLE_RATE) * Short.MAX_VALUE * gain * envelope).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[byteIndex++] = (value and 0xFF).toByte()
                bytes[byteIndex++] = ((value shr 8) and 0xFF).toByte()
            }
        }
        return bytes
    }

    private fun createTrack(bufferSize: Int, communicationSession: Boolean): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (communicationSession) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_MEDIA
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STATIC,
            )
        }
    }

    private companion object {
        private const val SAMPLE_RATE = 24_000
        private const val FADE_SAMPLES = 480
        private const val MAX_START_LATENCY_MS = 700L
        private const val SLOW_RELEASE_WARNING_MS = 300L
    }
}
