package com.agent.voiceassistant.tools

import com.agent.voiceassistant.agent.AgentAction
import com.agent.voiceassistant.cloud.CloudSpeechClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class MainToolRegistry(
    private val executor: LocalToolExecutor,
) {
    enum class Profile {
        STANDALONE,
        CONNECTED,
        DIAGNOSTIC,
    }

    data class Execution(
        val call: CloudSpeechClient.ToolCall,
        val result: LocalToolExecutor.ToolResult,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun definitions(
        profile: Profile = Profile.STANDALONE,
        allowReasoningEscalation: Boolean,
    ): List<CloudSpeechClient.ToolDefinition> = buildList {
        add(memoryCreate())
        add(memorySearch())
        add(locationRefresh())
        add(weatherCurrent())
        add(webSearch())
        if (allowReasoningEscalation) add(reasoningEscalation())

        // Hub tools are added here once the connected profile has a live Hub client.
        if (profile == Profile.DIAGNOSTIC) {
            // Diagnostic currently changes event visibility, not the model's authority.
        }
    }

    fun isReasoningEscalation(call: CloudSpeechClient.ToolCall): Boolean =
        call.name == TOOL_REQUEST_DEEP_REASONING

    fun reasoningEscalationReason(call: CloudSpeechClient.ToolCall): String =
        (parseArguments(call.arguments)["reason"] as? JsonPrimitive)
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(200)
            ?: "模型判断当前问题需要深入分析"

    suspend fun execute(call: CloudSpeechClient.ToolCall): Execution {
        val payload = parseArguments(call.arguments)
        val legacyAction = when (call.name) {
            TOOL_MEMORY_CREATE -> "memory.create"
            TOOL_MEMORY_SEARCH -> "memory.search"
            TOOL_LOCATION_REFRESH -> "location.refresh"
            TOOL_WEATHER_CURRENT -> "weather.get_current"
            TOOL_WEB_SEARCH -> "web.search"
            else -> call.name
        }
        val action = AgentAction(
            actionType = legacyAction,
            payload = payload,
            rawJson = call.arguments,
        )
        return Execution(call, executor.execute(action))
    }

    fun normalizeLegacyAction(action: AgentAction, callId: String): CloudSpeechClient.ToolCall {
        val name = when (action.actionType) {
            "memory.create", "note.create" -> TOOL_MEMORY_CREATE
            "memory.search", "note.search" -> TOOL_MEMORY_SEARCH
            "location.refresh", "location.get_current" -> TOOL_LOCATION_REFRESH
            "weather.get_current", "weather.current" -> TOOL_WEATHER_CURRENT
            "web.search", "websearch", "web_search" -> TOOL_WEB_SEARCH
            else -> action.actionType.replace('.', '_')
        }
        return CloudSpeechClient.ToolCall(
            id = callId,
            name = name,
            arguments = action.payload.toString(),
        )
    }

    fun displayName(toolName: String): String = when (toolName) {
        TOOL_MEMORY_CREATE -> "写入记忆"
        TOOL_MEMORY_SEARCH -> "查询记忆"
        TOOL_LOCATION_REFRESH -> "刷新定位"
        TOOL_WEATHER_CURRENT -> "查询天气"
        TOOL_WEB_SEARCH -> "网络搜索"
        TOOL_REQUEST_DEEP_REASONING -> "开启深度思考"
        else -> toolName
    }

    fun audibleAcknowledgement(toolName: String): String? = when (toolName) {
        TOOL_WEB_SEARCH -> "我去搜一下。"
        TOOL_REQUEST_DEEP_REASONING -> "嗯，这个我想一下。"
        else -> null
    }

    private fun parseArguments(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull()
            ?: JsonObject(
                mapOf(
                    "_invalid_arguments" to JsonPrimitive(raw.take(500)),
                ),
            )
    }

    private fun memoryCreate() = tool(
        name = TOOL_MEMORY_CREATE,
        description = "当用户明确要求记住、记录或保存某项信息时，将内容写入本地长期记忆。",
        required = listOf("content"),
    ) {
        putJsonObject("content") {
            put("type", "string")
            put("description", "需要记住的完整内容")
        }
        putJsonObject("tags") {
            put("type", "array")
            put("description", "可选的简短分类标签")
            put("items", buildJsonObject { put("type", "string") })
        }
    }

    private fun memorySearch() = tool(
        name = TOOL_MEMORY_SEARCH,
        description = "查询用户此前要求保存的本地记忆。",
    ) {
        putJsonObject("query") {
            put("type", "string")
            put("description", "查询关键词；为空时返回最近记忆")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 10)
        }
    }

    private fun locationRefresh() = tool(
        name = TOOL_LOCATION_REFRESH,
        description = "强制刷新手机当前位置。仅在用户询问或要求更新位置时调用。",
    ) {}

    private fun weatherCurrent() = tool(
        name = TOOL_WEATHER_CURRENT,
        description = "查询当前天气。未指定地点时使用手机当前位置。",
    ) {
        putJsonObject("location") {
            put("type", "string")
            put("description", "用户指定的城市或地点，可省略")
        }
    }

    private fun webSearch() = tool(
        name = TOOL_WEB_SEARCH,
        description = "搜索实时公开网络信息。用于近期变化、新闻、产品资料和需要核实的公开事实；禁止在查询词中加入私人信息。",
        required = listOf("query"),
    ) {
        putJsonObject("query") {
            put("type", "string")
            put("description", "包含必要时间范围的准确搜索词")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 5)
        }
    }

    private fun reasoningEscalation() = tool(
        name = TOOL_REQUEST_DEEP_REASONING,
        description = "仅当当前用户问题确实需要多步分析、方案权衡或头脑风暴时，为本回合申请一次深度思考。简单问答和普通工具调用不得使用。",
        required = listOf("reason"),
    ) {
        putJsonObject("reason") {
            put("type", "string")
            put("description", "需要深度思考的简短原因，不向用户播报")
        }
    }

    private fun tool(
        name: String,
        description: String,
        required: List<String> = emptyList(),
        properties: JsonObjectBuilder.() -> Unit,
    ): CloudSpeechClient.ToolDefinition {
        val propertyObject = buildJsonObject(properties)
        val parameters = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", propertyObject)
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }
        return CloudSpeechClient.ToolDefinition(name, description, parameters)
    }

    companion object {
        const val TOOL_MEMORY_CREATE = "memory_create"
        const val TOOL_MEMORY_SEARCH = "memory_search"
        const val TOOL_LOCATION_REFRESH = "location_refresh"
        const val TOOL_WEATHER_CURRENT = "weather_get_current"
        const val TOOL_WEB_SEARCH = "web_search"
        const val TOOL_REQUEST_DEEP_REASONING = "request_deep_reasoning"
    }
}
