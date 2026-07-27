package com.agent.voiceassistant.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object ToolCallSafety {
    private const val MAX_TOOL_CALL_ID_CHARS = 256
    private const val MAX_TOOL_ARGUMENT_CHARS = 64_000
    private val TOOL_NAME = Regex("[A-Za-z0-9_-]{1,128}")

    fun invalidReason(
        call: CloudSpeechClient.ToolCall,
        allowedToolNames: Set<String>? = null,
    ): String? = when {
        call.id.isBlank() -> "tool_call_id 为空"
        call.id.length > MAX_TOOL_CALL_ID_CHARS -> "tool_call_id 过长"
        !TOOL_NAME.matches(call.name) -> "工具名称格式非法"
        allowedToolNames != null && call.name !in allowedToolNames -> "工具未在本轮注册"
        call.arguments.length > MAX_TOOL_ARGUMENT_CHARS -> "工具参数过长"
        parseArguments(call.arguments) == null -> "工具参数不是合法的 JSON 对象"
        else -> null
    }

    fun requireValid(call: CloudSpeechClient.ToolCall) {
        val reason = invalidReason(call) ?: return
        throw MalformedToolCallException(call.id, call.name, reason)
    }

    private fun parseArguments(arguments: String): JsonObject? = runCatching {
        Json.parseToJsonElement(arguments) as? JsonObject
    }.getOrNull()
}

internal class MalformedToolCallException(
    toolCallId: String,
    toolName: String,
    reason: String,
) : IllegalArgumentException(
    "非法工具调用已被拦截：id=${toolCallId.take(80)} name=${toolName.take(80)} reason=$reason",
)
