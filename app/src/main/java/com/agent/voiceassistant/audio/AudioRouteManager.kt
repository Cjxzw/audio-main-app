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
import timber.log.Timber

class AudioRouteManager(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile private var configured = false
    @Volatile private var detectedInput: AudioDeviceInfo? = null
    @Volatile private var detectedOutput: AudioDeviceInfo? = null

    fun configureForVoiceSession(): String {
        val inputs = getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val communicationOutputs = getCommunicationDevices()

        detectedInput = inputs
            .filter { it.isSource }
            .minByOrNull { inputPriority(it) }
        detectedOutput = (communicationOutputs.ifEmpty { outputs.toList() })
            .filter { it.isSink }
            .minByOrNull { outputPriority(it) }

        configured = true
        val external = detectedInput?.isPreferredExternalInput() == true ||
            detectedOutput?.isPreferredExternalOutput() == true
        val summary = "候选输入=${deviceLabel(detectedInput)}，候选输出=${deviceLabel(detectedOutput)}"
        Timber.i(
            "AudioRoute diagnostics: system default routing is respected; $summary; " +
                "inputs=${inputs.joinToString { deviceLabel(it) }}; " +
                "outputs=${outputs.joinToString { deviceLabel(it) }}; " +
                "communication=${communicationOutputs.joinToString { deviceLabel(it) }}"
        )
        return if (external) {
            "检测到外部音频设备: $summary"
        } else {
            "未检测到外部音频设备，使用手机音频: $summary"
        }
    }

    fun ensureConfigured(): String? {
        if (configured) return null
        return configureForVoiceSession()
    }

    fun preferredAudioSource(): Int {
        return MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    fun applyInputRouting(record: AudioRecord) {
        Timber.i("AudioRoute: AudioRecord uses system default input route")
    }

    fun logRecordRoute(record: AudioRecord, label: String) {
        val routed = runCatching { record.routedDevice }.getOrNull()
        Timber.i("AudioRoute: AudioRecord $label routed=${deviceLabel(routed)}")
    }

    fun applyOutputRouting(track: AudioTrack) {
        applyOutputRouting(track as AudioRouting, "AudioTrack")
    }

    fun applyOutputRouting(player: MediaPlayer) {
        applyOutputRouting(player as AudioRouting, "MediaPlayer")
    }

    fun release() {
        configured = false
        detectedInput = null
        detectedOutput = null
        Timber.i("AudioRoute released")
    }

    private fun applyOutputRouting(routing: AudioRouting, owner: String) {
        Timber.i("AudioRoute: $owner uses system default output route")
    }

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
        bleSpeakerType() -> 7
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
}
