package com.agent.voiceassistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt
import timber.log.Timber

/** Keeps one AudioRecord alive and emits 20 ms PCM16 frames for a Realtime WebSocket. */
class RealtimeAudioRecorder(
    private val routeManager: AudioRouteManager,
    private val onPcmFrame: suspend (ByteArray) -> Unit,
    private val onVolume: (Float) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    @Volatile private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun capture() = withContext(Dispatchers.IO) {
        check(running.compareAndSet(false, true)) { "Realtime 音频采集已启动" }
        val active = createRecord()
        record = active
        try {
            routeManager.applyInputRouting(active)
            active.startRecording()
            val samples = ShortArray(FRAME_SAMPLES)
            while (running.get()) {
                coroutineContext.ensureActive()
                val read = active.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (read < 0) error("Realtime 麦克风读取失败，错误码 $read")
                if (read == 0) continue
                onVolume(rms(samples, read))
                onPcmFrame(samples.toPcmBytes(read))
            }
        } finally {
            running.set(false)
            onVolume(0f)
            record = null
            runCatching { active.stop() }
            active.release()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { record?.stop() }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        return sources.firstNotNullOfOrNull { source ->
            runCatching {
                AudioRecord(
                    source,
                    AudioConfig.INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    AudioConfig.INPUT_BUFFER_SIZE.coerceAtLeast(FRAME_BYTES * 4),
                ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
        } ?: error("无法初始化 Realtime 麦克风")
    }

    private fun ShortArray.toPcmBytes(length: Int): ByteArray = ByteBuffer
        .allocate(length * Short.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .also { buffer -> repeat(length) { index -> buffer.putShort(this[index]) } }
        .array()

    private fun rms(samples: ShortArray, length: Int): Float {
        if (length == 0) return 0f
        var sum = 0.0
        repeat(length) { index -> sum += samples[index].toDouble() * samples[index] }
        return (sqrt(sum / length) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val FRAME_DURATION_MS = 20
        const val FRAME_SAMPLES = AudioConfig.INPUT_SAMPLE_RATE * FRAME_DURATION_MS / 1_000
        const val FRAME_BYTES = FRAME_SAMPLES * Short.SIZE_BYTES
    }
}

/**
 * PCM16 output queue for StepFun's realtime audio deltas.
 *
 * Socket callbacks only enqueue packets. A dedicated IO worker owns AudioTrack writes, matching
 * the official console's WebAudio worklet model and preventing network dispatch from starving
 * playback. The final short tail is held until audio.done so it can fade to silence cleanly.
 */
class RealtimePcmPlayer(
    private val routeManager: AudioRouteManager,
    scope: CoroutineScope,
    private val sampleRate: Int = OUTPUT_SAMPLE_RATE,
) {
    private sealed interface Event {
        data class Audio(val generation: Long, val pcm: ByteArray) : Event
        data class AudioDone(val generation: Long) : Event
        data class Interrupt(val generation: Long) : Event
        data class Drain(val done: CompletableDeferred<Unit>) : Event
        data object Stop : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val generation = AtomicLong(0L)
    private val released = AtomicBoolean(false)
    @Volatile private var track: AudioTrack? = null
    private val worker: Job = scope.launch(Dispatchers.IO) { playLoop() }

    fun enqueue(pcm16Le: ByteArray) {
        if (pcm16Le.isEmpty()) return
        check(!released.get()) { "Realtime PCM 播放器已释放" }
        check(events.trySend(Event.Audio(generation.get(), pcm16Le)).isSuccess) { "Realtime PCM 队列不可用" }
    }

    fun finishResponseAudio() {
        if (released.get()) return
        events.trySend(Event.AudioDone(generation.get()))
    }

    /** Drops pending packets promptly; the worker fades only its currently held tail. */
    fun interrupt() {
        if (released.get()) return
        events.trySend(Event.Interrupt(generation.incrementAndGet()))
    }

    suspend fun awaitPlaybackDrained() {
        if (released.get()) return
        val done = CompletableDeferred<Unit>()
        if (events.trySend(Event.Drain(done)).isSuccess) {
            // A released worker may never consume a queued barrier. Bound that stale wait.
            while (!released.get() && withTimeoutOrNull(1_000) { done.await(); true } != true) Unit
        }
    }

    fun playedAudioMs(): Long {
        val frames = track?.playbackHeadPosition?.toLong()?.and(0xffff_ffffL) ?: return 0L
        return frames * 1_000L / sampleRate
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        events.trySend(Event.Stop)
        worker.cancel()
        track?.let { active ->
            runCatching { active.pause() }
            runCatching { active.flush() }
            runCatching { active.release() }
        }
        track = null
    }

    private suspend fun playLoop() {
        var activeGeneration = generation.get()
        var started = false
        var firstPacket = true
        var bufferedBytes = 0
        val startupBuffer = ArrayDeque<ByteArray>()
        var heldTail = ByteArray(0)
        var writtenFrames = 0L

        fun reset(dropTrackBuffer: Boolean) {
            startupBuffer.clear()
            bufferedBytes = 0
            heldTail = ByteArray(0)
            started = false
            firstPacket = true
            if (dropTrackBuffer) {
                track?.let { active ->
                    runCatching { active.pause() }
                    runCatching { active.flush() }
                    writtenFrames = 0L
                }
            }
        }

        fun writeFully(active: AudioTrack, pcm: ByteArray) {
            var offset = 0
            while (offset < pcm.size) {
                val written = active.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                check(written > 0) { "Realtime AudioTrack 写入失败：$written" }
                offset += written
                writtenFrames += written / Short.SIZE_BYTES
            }
        }

        fun writeContinuous(pcm: ByteArray) {
            val active = track ?: createTrack().also { track = it }
            val combined = if (heldTail.isEmpty()) pcm else heldTail + pcm
            if (combined.size <= FADE_BYTES) {
                heldTail = combined
                return
            }
            val bodyEnd = combined.size - FADE_BYTES
            val body = combined.copyOfRange(0, bodyEnd)
            if (firstPacket) {
                fadePcm16InPlace(body, fadeIn = true)
                firstPacket = false
            }
            if (active.playState != AudioTrack.PLAYSTATE_PLAYING) active.play()
            writeFully(active, body)
            heldTail = combined.copyOfRange(bodyEnd, combined.size)
        }

        fun startBufferedAudio() {
            if (started) return
            started = true
            while (startupBuffer.isNotEmpty()) writeContinuous(startupBuffer.removeFirst())
        }

        fun finishAudio() {
            if (!started && startupBuffer.isNotEmpty()) startBufferedAudio()
            val active = track
            if (active != null && heldTail.isNotEmpty()) {
                fadePcm16InPlace(heldTail, fadeIn = false)
                if (active.playState != AudioTrack.PLAYSTATE_PLAYING) active.play()
                writeFully(active, heldTail)
                writeFully(active, ByteArray(sampleRate * Short.SIZE_BYTES * FINAL_SILENCE_MS / 1_000))
            }
            Timber.i("RealtimePcmPlayer: audio.done drained started=$started")
            reset(dropTrackBuffer = false)
        }

        try {
            for (event in events) {
                when (event) {
                    is Event.Audio -> {
                        if (event.generation != generation.get()) continue
                        activeGeneration = event.generation
                        if (!started) {
                            startupBuffer.addLast(event.pcm)
                            bufferedBytes += event.pcm.size
                            if (bufferedBytes >= STARTUP_BUFFER_BYTES) startBufferedAudio()
                        } else {
                            writeContinuous(event.pcm)
                        }
                    }
                    is Event.AudioDone -> if (event.generation == generation.get()) finishAudio()
                    is Event.Interrupt -> {
                        activeGeneration = event.generation
                        reset(dropTrackBuffer = true)
                        Timber.i("RealtimePcmPlayer: interrupted generation=$activeGeneration")
                    }
                    is Event.Drain -> {
                        try {
                            while (!released.get() && activeGeneration == generation.get()) {
                                val frames = track?.playbackHeadPosition?.toLong()?.and(0xffff_ffffL) ?: writtenFrames
                                if (frames >= writtenFrames) break
                                delay(20)
                            }
                        } finally { event.done.complete(Unit) }
                    }
                    Event.Stop -> break
                }
            }
        } finally {
            track?.let { active ->
                runCatching { active.pause() }
                runCatching { active.flush() }
                runCatching { active.release() }
            }
            track = null
        }
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 5 * Short.SIZE_BYTES)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also(routeManager::applyOutputRouting)
    }

    private fun fadePcm16InPlace(bytes: ByteArray, fadeIn: Boolean) {
        val sampleCount = (bytes.size / Short.SIZE_BYTES).coerceAtMost(FADE_BYTES / Short.SIZE_BYTES)
        if (sampleCount <= 1) return
        repeat(sampleCount) { index ->
            val offset = if (fadeIn) index * Short.SIZE_BYTES else bytes.size - FADE_BYTES + index * Short.SIZE_BYTES
            if (offset < 0 || offset + 1 >= bytes.size) return@repeat
            val sample = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xFF)).toShort().toInt()
            val ratio = if (fadeIn) {
                index.toFloat() / (sampleCount - 1)
            } else {
                (sampleCount - 1 - index).toFloat() / (sampleCount - 1)
            }
            val faded = (sample * ratio).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[offset] = (faded and 0xFF).toByte()
            bytes[offset + 1] = ((faded shr 8) and 0xFF).toByte()
        }
    }

    private companion object {
        const val OUTPUT_SAMPLE_RATE = 24_000
        const val STARTUP_BUFFER_MS = 120
        const val FINAL_SILENCE_MS = 80
        const val FADE_MS = 18
        const val STARTUP_BUFFER_BYTES = OUTPUT_SAMPLE_RATE * Short.SIZE_BYTES * STARTUP_BUFFER_MS / 1_000
        const val FADE_BYTES = OUTPUT_SAMPLE_RATE * Short.SIZE_BYTES * FADE_MS / 1_000
    }
}
