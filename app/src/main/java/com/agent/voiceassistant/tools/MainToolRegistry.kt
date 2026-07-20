package com.agent.voiceassistant.tools

import com.agent.voiceassistant.agent.AgentAction
import com.agent.voiceassistant.cloud.CloudSpeechClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
        add(locationReverseGeocode())
        add(weatherCurrent())
        add(webSearch())
        add(voiceReply())
        add(readFile())
        add(writeFile())
        add(execCommand())
        add(httpRequest())
        add(codeGraphSearch())
        add(codeGraphExplain())
        if (allowReasoningEscalation) add(reasoningEscalation())

        // Hub tools are added here once the connected profile has a live Hub client.
        if (profile == Profile.DIAGNOSTIC) {
            // Diagnostic currently changes event visibility, not the model's authority.
        }
    }

    fun isReasoningEscalation(call: CloudSpeechClient.ToolCall): Boolean =
        call.name == TOOL_REQUEST_DEEP_REASONING

    fun isTerminalPresentation(call: CloudSpeechClient.ToolCall): Boolean =
        call.name == TOOL_VOICE_REPLY

    fun reasoningEscalationReason(call: CloudSpeechClient.ToolCall): String =
        (parseArguments(call.arguments)["reason"] as? JsonPrimitive)
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(200)
            ?: "模型判断当前问题需要深入分析"

    fun canExecuteInParallel(call: CloudSpeechClient.ToolCall): Boolean = when (call.name) {
        TOOL_HTTP_REQUEST -> {
            val method = (parseArguments(call.arguments)["method"] as? JsonPrimitive)
                ?.content
                ?.uppercase()
                ?: "GET"
            method == "GET" || method == "HEAD"
        }
        else -> call.name in PARALLEL_SAFE_TOOL_NAMES
    }

    suspend fun execute(call: CloudSpeechClient.ToolCall): Execution {
        val payload = parseArguments(call.arguments)
        val legacyAction = when (call.name) {
            TOOL_MEMORY_CREATE -> "memory.create"
            TOOL_MEMORY_SEARCH -> "memory.search"
            TOOL_LOCATION_REFRESH -> "location.refresh"
            TOOL_LOCATION_REVERSE_GEOCODE -> "location.reverse_geocode"
            TOOL_WEATHER_CURRENT -> "weather.get_current"
            TOOL_WEB_SEARCH -> "web.search"
            TOOL_READ -> "read"
            TOOL_WRITE -> "write"
            TOOL_EXEC -> "exec"
            TOOL_HTTP_REQUEST -> "http_request"
            TOOL_CODE_GRAPH_SEARCH -> "code.graph.search"
            TOOL_CODE_GRAPH_EXPLAIN -> "code.graph.explain"
            else -> call.name
        }
        val action = AgentAction(
            actionType = legacyAction,
            payload = payload,
            rawJson = call.arguments,
        )
        return Execution(call, executor.execute(action))
    }

    fun displayName(toolName: String): String = when (toolName) {
        TOOL_MEMORY_CREATE -> "写入记忆"
        TOOL_MEMORY_SEARCH -> "查询记忆"
        TOOL_LOCATION_REFRESH -> "刷新定位"
        TOOL_LOCATION_REVERSE_GEOCODE -> "解析当前位置"
        TOOL_WEATHER_CURRENT -> "查询天气"
        TOOL_WEB_SEARCH -> "网络搜索"
        TOOL_READ -> "读取文件"
        TOOL_WRITE -> "写入文件"
        TOOL_EXEC -> "执行命令"
        TOOL_HTTP_REQUEST -> "发送网络请求"
        TOOL_CODE_GRAPH_SEARCH -> "查询代码图谱"
        TOOL_CODE_GRAPH_EXPLAIN -> "解释代码符号"
        TOOL_REQUEST_DEEP_REASONING -> "开启深度思考"
        TOOL_VOICE_REPLY -> "个性化播报"
        TOOL_PROTOCOL_REPAIR -> "修正工具调用格式"
        else -> toolName
    }

    fun displaySummary(call: CloudSpeechClient.ToolCall): String? {
        val payload = parseArguments(call.arguments)
        val value = when (call.name) {
            TOOL_MEMORY_CREATE -> payload.text("content")?.let { "保存 ${it.take(24)}" }
            TOOL_MEMORY_SEARCH -> payload.text("query")
            TOOL_LOCATION_REFRESH -> "获取缓存"
            TOOL_LOCATION_REVERSE_GEOCODE -> "解析地址"
            TOOL_WEATHER_CURRENT -> listOfNotNull(payload.text("location"), payload.text("date"))
                .joinToString(" · ")
                .ifBlank { "当前位置" }
            TOOL_WEB_SEARCH -> payload.text("query")
            TOOL_READ -> payload.text("path")?.trimEnd('/')?.substringAfterLast('/')
                ?: (payload["paths"] as? JsonArray)?.size?.let { "$it 个项目" }
            TOOL_WRITE -> payload.text("path")?.trimEnd('/')?.substringAfterLast('/')
            TOOL_EXEC -> payload.text("command")?.lineSequence()?.firstOrNull()
            TOOL_HTTP_REQUEST -> payload.text("url")
            TOOL_CODE_GRAPH_SEARCH -> payload.text("query")
            TOOL_CODE_GRAPH_EXPLAIN -> payload.text("symbol")
            TOOL_REQUEST_DEEP_REASONING -> "当前回合"
            else -> null
        }
        return value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.length <= MAX_DISPLAY_SUMMARY_CHARS) it else it.take(MAX_DISPLAY_SUMMARY_CHARS - 3) + "..." }
    }

    fun countsTowardAutomaticReasoning(call: CloudSpeechClient.ToolCall): Boolean =
        call.name != TOOL_REQUEST_DEEP_REASONING &&
            call.name != TOOL_PROTOCOL_REPAIR &&
            call.name != TOOL_VOICE_REPLY

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

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }

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
        description = "获取手机当前位置缓存，并在允许时后台发起一次刷新。返回经纬度、精度、来源、定位时间和缓存状态；已有缓存时不等待刷新。启动或新话题也会自动后台刷新，成功后 5 分钟内不会重复请求系统定位。",
    ) {}

    private fun locationReverseGeocode() = tool(
        name = TOOL_LOCATION_REVERSE_GEOCODE,
        description = "将当前位置缓存中的经纬度解析为人类可读地址。只有用户询问街道、地址或当前位置名称时调用；天气、距离和地图类任务直接使用 location_refresh 返回的经纬度，不要先调用本工具。",
    ) {}

    private fun weatherCurrent() = tool(
        name = TOOL_WEATHER_CURRENT,
        description = "查询指定日期的天气预报和逐小时变化。未指定地点时使用手机当前位置；日期可填今天、明天、后天或 YYYY-MM-DD。",
    ) {
        putJsonObject("location") {
            put("type", "string")
            put("description", "用户指定的城市或地点，可省略")
        }
        putJsonObject("date") {
            put("type", "string")
            put("description", "目标日期：今天、明天、后天或 YYYY-MM-DD；省略时查询今天")
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
        description = "为当前用户回合申请一次深度思考。适用于多步分析、方案权衡、复杂排障、头脑风暴，或需要明显提高回答质量的情况；简单问答不得使用。可以与参数互不依赖的其他工具在同一批次调用。",
        required = listOf("reason"),
    ) {
        putJsonObject("reason") {
            put("type", "string")
            put("description", "需要深度思考的简短原因，不向用户播报")
        }
    }

    private fun voiceReply() = tool(
        name = TOOL_VOICE_REPLY,
        description = "终止型个性化语音回复。仅在用户明确要求唱歌、临时更换音色、模仿特殊说话风格或设计新音色时使用。调用即作为本回合最终答复：正文必须为空，完整可见且可播报的回复全部写入 text，App 不会再请求模型总结。preset 支持四种内置音色及唱歌；design 根据 voice_prompt 临时设计音色，但不支持唱歌。普通问答不要调用。",
        required = listOf("text", "mode"),
    ) {
        putJsonObject("text") {
            put("type", "string")
            put("description", "本回合完整最终回复；不得在普通正文中重复输出")
        }
        putJsonObject("mode") {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive("preset")); add(JsonPrimitive("design")) })
        }
        putJsonObject("voice") {
            put("type", "string")
            put("enum", buildJsonArray {
                listOf("冰糖", "茉莉", "苏打", "白桦").forEach { add(JsonPrimitive(it)) }
            })
            put("description", "preset 模式的内置音色，省略时使用冰糖")
        }
        putJsonObject("performance") {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive("speech")); add(JsonPrimitive("singing")) })
            put("description", "preset 可选；design 只能 speech")
        }
        putJsonObject("style_prompt") {
            put("type", "string")
            put("description", "preset 模式的语气、节奏、情绪要求")
        }
        putJsonObject("voice_prompt") {
            put("type", "string")
            put("description", "design 模式必填，描述目标音色、年龄感、音调和说话风格")
        }
    }

    private fun readFile() = tool(
        name = TOOL_READ,
        description = "读取虚拟文件系统中的一个或多个文本文件，也可列出目录。读取单项时传 path；同一步需要读取多个独立文件时优先在一次调用中传 paths，最多 10 项，不要拆成多个并行 read。源码使用 /source，日志使用 /logs，工作文件使用 /workspace，Skill 使用 /skills。必须使用这些虚拟路径；日志优先使用 tail_lines，大文件使用 offset 和 limit 分段读取。",
    ) {
        putJsonObject("path") {
            put("type", "string")
            put("description", "绝对虚拟路径，例如 /logs/voice-agent.log")
        }
        putJsonObject("paths") {
            put("type", "array")
            put("minItems", 1)
            put("maxItems", 10)
            put("description", "需要同时读取的绝对虚拟路径列表；与 path 二选一")
            put("items", buildJsonObject {
                put("type", "string")
            })
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

    private fun codeGraphSearch() = tool(
        name = TOOL_CODE_GRAPH_SEARCH,
        description = "查询随 APK 分发的 Graphify 代码图谱，用于定位源码符号、文件关系和调用线索。图谱只用于导航，最终结论必须核对源码或日志。",
        required = listOf("query"),
    ) {
        putJsonObject("query") {
            put("type", "string")
            put("description", "代码问题、类名、方法名或功能关键词")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 12)
        }
    }

    private fun codeGraphExplain() = tool(
        name = TOOL_CODE_GRAPH_EXPLAIN,
        description = "解释某个源码符号在 Graphify 图谱中的位置和关联节点；解释后仍需读取精确源码。",
        required = listOf("symbol"),
    ) {
        putJsonObject("symbol") {
            put("type", "string")
            put("description", "类名、对象名、函数名或文件名")
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
        const val TOOL_LOCATION_REVERSE_GEOCODE = "location_reverse_geocode"
        const val TOOL_WEATHER_CURRENT = "weather_get_current"
        const val TOOL_WEB_SEARCH = "web_search"
        const val TOOL_READ = "read"
        const val TOOL_WRITE = "write"
        const val TOOL_EXEC = "exec"
        const val TOOL_HTTP_REQUEST = "http_request"
        const val TOOL_CODE_GRAPH_SEARCH = "code_graph_search"
        const val TOOL_CODE_GRAPH_EXPLAIN = "code_graph_explain"
        const val TOOL_REQUEST_DEEP_REASONING = "request_deep_reasoning"
        const val TOOL_VOICE_REPLY = "voice_reply"
        const val TOOL_PROTOCOL_REPAIR = "__repair_tool_protocol"

        private const val MAX_DISPLAY_SUMMARY_CHARS = 48

        private val PARALLEL_SAFE_TOOL_NAMES = setOf(
            TOOL_MEMORY_SEARCH,
            TOOL_WEB_SEARCH,
            TOOL_READ,
            TOOL_CODE_GRAPH_SEARCH,
            TOOL_CODE_GRAPH_EXPLAIN,
        )

    }
}
