package com.agent.voiceassistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.delay
import timber.log.Timber

class AudioRouteManager(context: Context) {
    data class ExternalOutput(
        val connected: Boolean,
        val label: String,
    )
    data class RouteReadiness(
        val ready: Boolean,
        val elapsedMs: Long,
        val summary: String,
    )

    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile private var configured = false
    @Volatile private var detectedInput: AudioDeviceInfo? = null
    @Volatile private var detectedOutput: AudioDeviceInfo? = null
    @Volatile private var previousMode: Int? = null
    @Volatile private var communicationDeviceSet = false
    @Volatile private var scoStarted = false
    @Volatile private var playbackOnly = false

    val communicationSession: Boolean get() = configured && !playbackOnly

    fun configureForVoiceSession(): String {
        playbackOnly = false
        prepareCommunicationRoute()

        val inputs = getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val communicationOutputs = getCommunicationDevices()

        detectedInput = inputs
            .filter { it.isSource }
            .minByOrNull { inputPriority(it) }
        detectedOutput = (communicationOutputs.ifEmpty { outputs.toList() })
            .filter { it.isSink }
            .minByOrNull { outputPriority(it) }

        applyCommunicationDevice(detectedOutput)

        configured = true
        val external = detectedInput?.isPreferredExternalInput() == true ||
            detectedOutput?.isPreferredExternalOutput() == true
        val summary = "候选输入=${deviceLabel(detectedInput)}，候选输出=${deviceLabel(detectedOutput)}"
        Timber.i(
            "AudioRoute diagnostics: external routing preferred; $summary; " +
                "inputs=${inputs.joinToString { deviceLabel(it) }}; " +
                "outputs=${outputs.joinToString { deviceLabel(it) }}; " +
                "communication=${communicationOutputs.joinToString { deviceLabel(it) }}"
        )
        return if (external) {
            "已优先使用外部音频设备: $summary"
        } else {
            "使用手机麦克风和扬声器；建议连接外部麦克风以获得更好的收音和对话效果: $summary"
        }
    }

    fun ensureConfigured(): String? {
        if (configured) return null
        return configureForVoiceSession()
    }

    suspend fun awaitVoiceRoute(
        timeoutMs: Long = ROUTE_READY_TIMEOUT_MS,
        pollMs: Long = ROUTE_READY_POLL_MS,
    ): RouteReadiness {
        ensureConfigured()
        ensureCommunicationMode("await_voice_route")
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
            if (communicationRouteReady()) {
                val summary = currentRouteSummary()
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Timber.i("AudioRoute: communication route ready elapsedMs=$elapsed $summary")
                return RouteReadiness(true, elapsed, summary)
            }
            delay(pollMs)
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val failedRoute = currentRouteSummary()
        Timber.w("AudioRoute: communication route timeout elapsedMs=$elapsed $failedRoute")
        val fallback = fallbackToPhoneAudio("communication_route_timeout")
        return RouteReadiness(
            ready = fallback != null,
            elapsedMs = elapsed,
            summary = fallback ?: failedRoute,
        )
    }

    suspend fun awaitInputRoute(
        record: AudioRecord,
        timeoutMs: Long = INPUT_ROUTE_TIMEOUT_MS,
        pollMs: Long = ROUTE_READY_POLL_MS,
    ): Boolean {
        val expected = detectedInput
        if (expected?.isPreferredExternalInput() != true) return true
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
            val routed = runCatching { record.routedDevice }.getOrNull()
            if (routed != null && routed.type == expected.type) {
                Timber.i(
                    "AudioRoute: input route ready elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                        "expected=${deviceLabel(expected)} routed=${deviceLabel(routed)}"
                )
                return true
            }
            applyInputRouting(record)
            delay(pollMs)
        }
        val routed = runCatching { record.routedDevice }.getOrNull()
        Timber.w("AudioRoute: input route timeout expected=${deviceLabel(expected)} routed=${deviceLabel(routed)}")
        val fallback = fallbackToPhoneAudio("input_route_timeout") ?: return false
        if (!applyInputRouting(record)) return false

        val fallbackInput = detectedInput
        val fallbackStartedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - fallbackStartedAt < PHONE_INPUT_READY_TIMEOUT_MS) {
            val fallbackRouted = runCatching { record.routedDevice }.getOrNull()
            if (fallbackRouted != null && fallbackRouted.type == fallbackInput?.type) {
                Timber.i(
                    "AudioRoute: phone input route ready elapsedMs=${SystemClock.elapsedRealtime() - fallbackStartedAt} " +
                        "routed=${deviceLabel(fallbackRouted)} $fallback"
                )
                return true
            }
            delay(pollMs)
        }
        Timber.w("AudioRoute: phone input route unavailable routed=${deviceLabel(record.routedDevice)}")
        return false
    }

    fun preferredAudioSource(): Int {
        return if (detectedInput?.isPreferredExternalInput() == true) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    fun applyInputRouting(record: AudioRecord): Boolean {
        ensureCommunicationMode("audio_record")
        val target = detectedInput
        if (target == null) {
            Timber.i("AudioRoute: no preferred input device; AudioRecord uses system default input route")
            return true
        }
        val ok = runCatching { record.setPreferredDevice(target) }
            .onFailure { Timber.w(it, "AudioRoute: AudioRecord setPreferredDevice failed target=${deviceLabel(target)}") }
            .getOrDefault(false)
        Timber.i("AudioRoute: AudioRecord setPreferredDevice target=${deviceLabel(target)} ok=$ok")
        return ok
    }

    fun logRecordRoute(record: AudioRecord, label: String) {
        val routed = runCatching { record.routedDevice }.getOrNull()
        Timber.i("AudioRoute: AudioRecord $label routed=${deviceLabel(routed)}")
    }

    fun logTrackRoute(track: AudioTrack, label: String) {
        val routed = runCatching { track.routedDevice }.getOrNull()
        Timber.i("AudioRoute: AudioTrack $label routed=${deviceLabel(routed)}")
    }

    fun applyOutputRouting(track: AudioTrack) {
        ensureCommunicationMode("audio_track")
        applyOutputRouting(track as AudioRouting, "AudioTrack")
    }

    fun applyOutputRouting(player: MediaPlayer) {
        ensureCommunicationMode("media_player")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            applyOutputRouting(player as AudioRouting, "MediaPlayer")
        } else {
            Timber.i("AudioRoute: MediaPlayer preferred routing requires API 28; using system route")
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceSet) {
            runCatching { audioManager.clearCommunicationDevice() }
                .onFailure { Timber.w(it, "AudioRoute: clearCommunicationDevice failed") }
        }
        if (scoStarted) {
            @Suppress("DEPRECATION")
            runCatching { audioManager.stopBluetoothSco() }
                .onFailure { Timber.w(it, "AudioRoute: stopBluetoothSco failed") }
        }
        previousMode?.let { oldMode ->
            runCatching { audioManager.mode = oldMode }
                .onFailure { Timber.w(it, "AudioRoute: restore audio mode failed") }
        }
        configured = false
        playbackOnly = false
        detectedInput = null
        detectedOutput = null
        previousMode = null
        communicationDeviceSet = false
        scoStarted = false
        Timber.i("AudioRoute released")
    }

    fun ensureCommunicationMode(reason: String): Boolean {
        if (!configured || playbackOnly) return false
        val current = audioManager.mode
        val protectedCallMode = current == AudioManager.MODE_IN_CALL ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && current == AudioManager.MODE_CALL_SCREENING)
        if (protectedCallMode) {
            Timber.w("AudioRoute: keep system call mode=$current reason=$reason")
            return false
        }
        if (current == AudioManager.MODE_IN_COMMUNICATION) return true
        return runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val applied = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
            Timber.i("AudioRoute: communication mode reasserted from=$current applied=$applied reason=$reason")
            applied
        }.onFailure {
            Timber.w(it, "AudioRoute: communication mode reassert failed from=$current reason=$reason")
        }.getOrDefault(false)
    }

    fun externalOutput(): ExternalOutput {
        val device = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && it.isPreferredExternalOutput() }
            .minByOrNull { outputPriority(it) }
        return ExternalOutput(device != null, deviceLabel(device))
    }

    fun configureForExternalPlayback(): ExternalOutput {
        val device = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && it.isPreferredExternalOutput() }
            .minByOrNull { outputPriority(it) }
        detectedInput = null
        detectedOutput = device
        configured = true
        playbackOnly = true
        return ExternalOutput(device != null, deviceLabel(device))
    }

    @Synchronized
    fun fallbackToPhoneAudio(reason: String): String? {
        val phoneInput = getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (phoneInput == null) {
            Timber.e("AudioRoute: phone fallback unavailable; no built-in microphone reason=$reason")
            return null
        }
        val phoneOutput = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        clearExternalCommunicationRoute()
        detectedInput = phoneInput
        detectedOutput = phoneOutput
        val summary = currentRouteSummary()
        Timber.w("AudioRoute: external route unavailable; falling back to phone reason=$reason $summary")
        return "外部音频设备不可用，已回退到手机麦克风和扬声器；手机收音质量可能影响对话效果: $summary"
    }

    private fun applyOutputRouting(routing: AudioRouting, owner: String) {
        val target = detectedOutput
        if (target == null) {
            Timber.i("AudioRoute: no preferred output device; $owner uses system default output route")
            return
        }
        val ok = runCatching { routing.setPreferredDevice(target) }
            .onFailure { Timber.w(it, "AudioRoute: $owner setPreferredDevice failed target=${deviceLabel(target)}") }
            .getOrDefault(false)
        Timber.i("AudioRoute: $owner setPreferredDevice target=${deviceLabel(target)} ok=$ok")
    }

    private fun prepareCommunicationRoute() {
        if (previousMode == null) {
            previousMode = audioManager.mode
        }
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }.onFailure {
            Timber.w(it, "AudioRoute: set MODE_IN_COMMUNICATION failed")
        }
    }

    private fun applyCommunicationDevice(device: AudioDeviceInfo?) {
        if (device == null || !device.isPreferredExternalOutput()) {
            Timber.i("AudioRoute: no external communication output selected")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = runCatching { audioManager.setCommunicationDevice(device) }
                .onFailure { Timber.w(it, "AudioRoute: setCommunicationDevice failed target=${deviceLabel(device)}") }
                .getOrDefault(false)
            communicationDeviceSet = ok
            Timber.i("AudioRoute: setCommunicationDevice target=${deviceLabel(device)} ok=$ok")
            return
        }

        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }.onSuccess {
                scoStarted = true
                Timber.i("AudioRoute: startBluetoothSco requested")
            }.onFailure {
                Timber.w(it, "AudioRoute: startBluetoothSco failed")
            }
        }
    }

    private fun clearExternalCommunicationRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
                .onFailure { Timber.w(it, "AudioRoute: clearCommunicationDevice for fallback failed") }
        }
        if (scoStarted) {
            @Suppress("DEPRECATION")
            runCatching { audioManager.stopBluetoothSco() }
                .onFailure { Timber.w(it, "AudioRoute: stopBluetoothSco for fallback failed") }
        }
        communicationDeviceSet = false
        scoStarted = false
    }

    private fun communicationRouteReady(): Boolean {
        val expected = detectedOutput
        if (expected?.isPreferredExternalOutput() != true) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.communicationDevice?.type == expected.type }.getOrDefault(false)
        } else if (expected.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn
        } else {
            true
        }
    }

    private fun currentRouteSummary(): String =
        "候选输入=${deviceLabel(detectedInput)}，候选输出=${deviceLabel(detectedOutput)}"

    private fun getDevices(flags: Int): Array<AudioDeviceInfo> {
        return runCatching { audioManager.getDevices(flags) }
            .onFailure { Timber.w(it, "AudioRoute: getDevices($flags) failed") }
            .getOrDefault(emptyArray())
    }

    private fun getCommunicationDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return runCatching { audioManager.availableCommunicationDevices }
            .onFailure { Timber.w(it, "AudioRoute: availableCommunicationDevices failed") }
            .getOrDefault(emptyList())
    }

    private fun inputPriority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0
        bleHeadsetType() -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET -> 2
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
        AudioDeviceInfo.TYPE_USB_DEVICE -> 4
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 5
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 50
        else -> 40
    }

    private fun outputPriority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0
        bleHeadsetType() -> 1
        hearingAidType() -> 2
        AudioDeviceInfo.TYPE_USB_HEADSET -> 3
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 4
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 5
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 6
        AudioDeviceInfo.TYPE_USB_DEVICE -> 7
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 8
        bleSpeakerType() -> 9
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 50
        else -> 40
    }

    private fun AudioDeviceInfo.isPreferredExternalInput(): Boolean = inputPriority(this) < 40

    private fun AudioDeviceInfo.isPreferredExternalOutput(): Boolean = outputPriority(this) < 40

    private fun deviceLabel(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        val name = runCatching { device.productName?.toString().orEmpty() }
            .getOrDefault("")
            .ifBlank { "unknown" }
        return "${typeName(device.type)}:$name#${device.id}"
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        bleHeadsetType() -> "BLE_HEADSET"
        bleSpeakerType() -> "BLE_SPEAKER"
        hearingAidType() -> "HEARING_AID"
        else -> "TYPE_$type"
    }

    private fun bleHeadsetType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioDeviceInfo.TYPE_BLE_HEADSET else Int.MIN_VALUE

    private fun bleSpeakerType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioDeviceInfo.TYPE_BLE_SPEAKER else Int.MIN_VALUE + 1

    private fun hearingAidType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AudioDeviceInfo.TYPE_HEARING_AID else Int.MIN_VALUE + 2

    private companion object {
        private const val ROUTE_READY_TIMEOUT_MS = 1_800L
        private const val INPUT_ROUTE_TIMEOUT_MS = 1_500L
        private const val PHONE_INPUT_READY_TIMEOUT_MS = 800L
        private const val ROUTE_READY_POLL_MS = 100L
    }
}
