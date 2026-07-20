package com.agent.voiceassistant.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

data class VoiceReplyDirective(
    val text: String,
    val options: VoiceReplyOptions,
)

object VoiceReplyDirectiveParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawArguments: String): VoiceReplyDirective {
        val payload = runCatching { json.parseToJsonElement(rawArguments) as JsonObject }
            .getOrElse { throw IllegalArgumentException("voice_reply 参数不是有效 JSON") }
        val text = payload.text("text")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("voice_reply 缺少 text")
        require(text.length <= MAX_TEXT_CHARS) { "voice_reply text 不能超过 $MAX_TEXT_CHARS 字" }

        val mode = when (payload.text("mode")?.lowercase()) {
            null, "preset" -> VoiceReplyMode.PRESET
            "design" -> VoiceReplyMode.DESIGN
            else -> throw IllegalArgumentException("mode 只支持 preset 或 design")
        }
        val performance = when (payload.text("performance")?.lowercase()) {
            null, "speech" -> VoicePerformance.SPEECH
            "singing" -> VoicePerformance.SINGING
            else -> throw IllegalArgumentException("performance 只支持 speech 或 singing")
        }
        val stylePrompt = payload.text("style_prompt")
        val voicePrompt = payload.text("voice_prompt")
        val voice = payload.text("voice") ?: VoiceReplyOptions.DEFAULT_VOICE

        if (mode == VoiceReplyMode.PRESET) {
            require(voice in VoiceReplyOptions.PRESET_VOICES) {
                "预设音色只支持：${VoiceReplyOptions.PRESET_VOICES.joinToString("、")}"
            }
            require(voicePrompt == null) { "preset 模式不能传 voice_prompt" }
        } else {
            require(!voicePrompt.isNullOrBlank()) { "design 模式必须传 voice_prompt" }
            require(performance == VoicePerformance.SPEECH) { "design 模式暂不支持唱歌" }
        }

        return VoiceReplyDirective(
            text = text,
            options = VoiceReplyOptions(
                mode = mode,
                voice = voice,
                performance = performance,
                stylePrompt = stylePrompt,
                voicePrompt = voicePrompt,
            ),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }

    private const val MAX_TEXT_CHARS = 5_000
}
