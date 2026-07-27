package com.agent.voiceassistant.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal const val DEBUG_BRIDGE_ACTION = "com.agent.voiceassistant.debug.COMMAND"
internal const val DEBUG_BRIDGE_EXTRA_REQUEST_ID = "request_id"
internal const val DEBUG_BRIDGE_DIRECTORY = "debug-bridge"

@Serializable
internal data class DebugBridgeRequest(
    val version: Int = 1,
    val request_id: String,
    val command: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

internal object DebugBridgeProtocol {
    val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        prettyPrint = true
    }

    private val requestIdPattern = Regex("[A-Za-z0-9._-]{8,80}")

    fun requireValidRequestId(value: String): String =
        value.takeIf(requestIdPattern::matches)
            ?: throw IllegalArgumentException("request_id 格式无效")

    fun decodeRequest(value: String): DebugBridgeRequest {
        val request = json.decodeFromString<DebugBridgeRequest>(value)
        require(request.version == 1) { "不支持的调试协议版本：${request.version}" }
        requireValidRequestId(request.request_id)
        require(request.command.matches(Regex("[a-z][a-z0-9_.-]{1,63}"))) { "command 格式无效" }
        return request
    }
}
