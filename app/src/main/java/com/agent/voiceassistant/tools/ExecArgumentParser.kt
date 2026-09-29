package com.agent.voiceassistant.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Normalizes the native exec contract while tolerating one JSON-stringification layer. */
internal object ExecArgumentParser {
    private val json = Json { isLenient = true }

    fun parse(element: JsonElement?): List<String> {
        return when (element) {
            is JsonArray -> parseArray(element)
            is JsonPrimitive -> {
                val raw = element.contentOrNull?.trim().orEmpty()
                if (raw.isBlank()) return emptyList()
                val decoded = runCatching { json.parseToJsonElement(raw) }.getOrNull()
                if (decoded is JsonArray) parseArray(decoded)
                else throw IllegalArgumentException("exec 的 argv 必须是字符串数组；不接受 shell command 字符串")
            }
            else -> emptyList()
        }
    }

    private fun parseArray(array: JsonArray): List<String> = array.map { item ->
        (item as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("exec 的 argv 每一项都必须是非空字符串")
    }
}
