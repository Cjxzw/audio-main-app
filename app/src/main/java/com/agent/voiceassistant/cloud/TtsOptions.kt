package com.agent.voiceassistant.cloud

enum class VoiceReplyMode {
    PRESET,
    DESIGN,
}

enum class VoicePerformance {
    SPEECH,
    SINGING,
}

data class VoiceReplyOptions(
    val mode: VoiceReplyMode = VoiceReplyMode.PRESET,
    val voice: String = DEFAULT_VOICE,
    val performance: VoicePerformance = VoicePerformance.SPEECH,
    val stylePrompt: String? = null,
    val voicePrompt: String? = null,
) {
    companion object {
        const val DEFAULT_VOICE = "冰糖"
        val PRESET_VOICES = setOf("冰糖", "茉莉", "苏打", "白桦")
    }
}
