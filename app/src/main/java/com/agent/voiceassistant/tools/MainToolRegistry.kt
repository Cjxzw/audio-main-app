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
        add(readFile())
        add(writeFile())
        add(execCommand())
        add(httpRequest())
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
            TOOL_READ -> "read"
            TOOL_WRITE -> "write"
            TOOL_EXEC -> "exec"
            TOOL_HTTP_REQUEST -> "http_request"
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
            "read" -> TOOL_READ
            "write" -> TOOL_WRITE
            "exec", "shell", "bash" -> TOOL_EXEC
            "http.request", "http_request" -> TOOL_HTTP_REQUEST
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
        TOOL_READ -> "读取文件"
        TOOL_WRITE -> "写入文件"
        TOOL_EXEC -> "执行命令"
        TOOL_HTTP_REQUEST -> "发送网络请求"
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

    private fun readFile() = tool(
        name = TOOL_READ,
        description = "读取虚拟文件系统中的文本文件或列出目录。源码使用 /source，日志使用 /logs，工作文件使用 /workspace，Skill 使用 /skills。日志优先使用 tail_lines 读取末尾；大文件使用 offset 和 limit 分段读取。",
        required = listOf("path"),
    ) {
        putJsonObject("path") {
            put("type", "string")
            put("description", "绝对虚拟路径，例如 /logs/voice-agent.log")
        }
        putJsonObject("offset") {
            put("type", "integer")
            put("minimum", 1)
            put("description", "起始行号，从 1 开始")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 1000)
            put("description", "最多读取的行数")
        }
        putJsonObject("tail_lines") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 1000)
            put("description", "从文件末尾读取的行数，不能与 offset 同时使用；查看日志时优先使用")
        }
    }

    private fun writeFile() = tool(
        name = TOOL_WRITE,
        description = "在 /workspace 中创建、覆盖或追加 UTF-8 文本文件。不得写入源码、日志或 Skill 目录。",
        required = listOf("path", "content"),
    ) {
        putJsonObject("path") {
            put("type", "string")
            put("description", "必须位于 /workspace 的绝对虚拟路径")
        }
        putJsonObject("content") {
            put("type", "string")
            put("description", "需要写入的完整文本")
        }
        putJsonObject("mode") {
            put("type", "string")
            put("enum", buildJsonArray {
                add(JsonPrimitive("overwrite"))
                add(JsonPrimitive("append"))
                add(JsonPrimitive("create"))
            })
        }
    }

    private fun execCommand() = tool(
        name = TOOL_EXEC,
        description = "在 Android App 自身 UID 沙箱中执行一次性 shell 命令。通过 cwd 选择 /source、/logs、/workspace 或 /skills 虚拟工作目录；适合日志诊断、文本处理、网络探测和短脚本，不得启动驻留或交互进程。",
        required = listOf("command"),
    ) {
        putJsonObject("command") {
            put("type", "string")
            put("description", "传给系统 sh -lc 的命令；可使用 SOURCE_ROOT、LOGS_ROOT、WORKSPACE_ROOT、SKILLS_ROOT 环境变量")
        }
        putJsonObject("timeout_seconds") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 120)
        }
        putJsonObject("cwd") {
            put("type", "string")
            put("description", "虚拟工作目录，默认 /workspace。不要在 shell 命令中直接使用 /source 等虚拟绝对路径")
        }
    }

    private fun httpRequest() = tool(
        name = TOOL_HTTP_REQUEST,
        description = "发送通用 HTTP/HTTPS 请求，用于 API 调试和 Skill 调用。凭据只能通过 credential_profile 引用，不得要求用户把密钥放进参数。",
        required = listOf("url"),
    ) {
        putJsonObject("method") {
            put("type", "string")
            put("enum", buildJsonArray {
                listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
                    .forEach { add(JsonPrimitive(it)) }
            })
        }
        putJsonObject("url") {
            put("type", "string")
            put("description", "完整 URL；指定 credential_profile 且该 profile 配有基础地址时，也可传 /api/... 相对路径")
        }
        putJsonObject("body") { put("type", "string") }
        putJsonObject("content_type") { put("type", "string") }
        putJsonObject("credential_profile") {
            put("type", "string")
            put("description", "Android Keystore 中已配置的凭据别名")
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
        const val TOOL_READ = "read"
        const val TOOL_WRITE = "write"
        const val TOOL_EXEC = "exec"
        const val TOOL_HTTP_REQUEST = "http_request"
        const val TOOL_REQUEST_DEEP_REASONING = "request_deep_reasoning"
    }
}
