package com.agent.voiceassistant.tools

import com.agent.voiceassistant.agent.AgentAction
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.hub.HubRuntime
import com.agent.voiceassistant.tasks.AsyncTaskCoordinator
import com.agent.voiceassistant.tasks.SongGenerationExecutor
import com.agent.voiceassistant.tasks.TaskPriority
import com.agent.voiceassistant.tasks.TaskRepository
import com.agent.voiceassistant.tasks.TaskSubmission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

class MainToolRegistry(
    private val executor: LocalToolExecutor,
    private val taskCoordinator: AsyncTaskCoordinator? = null,
    private val taskRepository: TaskRepository? = null,
    private val taskContext: () -> TaskToolContext = { TaskToolContext("default", "") },
) {
    data class TaskToolContext(
        val conversationId: String,
        val sourceTurnId: String,
        val silentAudio: Boolean = false,
    )

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
    ): List<CloudSpeechClient.ToolDefinition> = listOf(
        memoryCreate(),
        memorySearch(),
        agentSleep(),
        hubDispatchTask(),
        reasoningEscalation(),
        skillUse(),
        webSearch(),
    )

    fun isReasoningEscalation(call: CloudSpeechClient.ToolCall): Boolean =
        call.name == TOOL_REQUEST_DEEP_REASONING

    fun isTerminalPresentation(call: CloudSpeechClient.ToolCall): Boolean =
        call.name == TOOL_VOICE_REPLY || call.name == TOOL_AGENT_SLEEP

    fun isAgentSleep(call: CloudSpeechClient.ToolCall): Boolean =
        call.name == TOOL_AGENT_SLEEP

    fun isNativeTool(name: String): Boolean = name in NATIVE_TOOL_NAMES
    fun isHiddenTool(name: String): Boolean = name in HIDDEN_TOOL_NAMES
    fun hiddenSkillId(name: String): String? = when (name) {
        TOOL_VOICE_REPLY -> SYSTEM_SKILL_ADVANCED_TTS
        in LOCAL_EXECUTION_TOOL_NAMES -> SYSTEM_SKILL_LOCAL_EXECUTION
        else -> null
    }

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
        if (call.name == TOOL_HUB_DISPATCH_TASK) {
            return Execution(call, executeHubDispatch(payload))
        }
        if (call.name in ASYNC_TASK_TOOL_NAMES) {
            return Execution(call, executeAsyncTaskTool(call, payload))
        }
        val legacyAction = when (call.name) {
            TOOL_MEMORY_CREATE -> "memory.create"
            TOOL_MEMORY_SEARCH -> "memory.search"
            TOOL_LOCATION_REFRESH -> "location.refresh"
            TOOL_LOCATION_REVERSE_GEOCODE -> "location.reverse_geocode"
            TOOL_WEATHER_CURRENT -> "weather.get_current"
            TOOL_WEB_SEARCH -> "web.search"
            TOOL_SKILL_USE -> "skill.use"
            TOOL_READ -> "read"
            TOOL_WRITE -> "write"
            TOOL_WORKSPACE_DELETE -> "workspace.delete"
            TOOL_EXEC -> "exec"
            TOOL_HTTP_REQUEST -> "http_request"
            TOOL_CODE_GRAPH_SEARCH -> "code.graph.search"
            TOOL_CODE_GRAPH_EXPLAIN -> "code.graph.explain"
            TOOL_SKILL_CREATE -> "skill.create"
            TOOL_SKILL_EDIT -> "skill.edit"
            TOOL_SKILL_REGISTER -> "skill.register"
            else -> call.name
        }
        val action = AgentAction(
            actionType = legacyAction,
            payload = payload,
            rawJson = call.arguments,
        )
        return Execution(call, executor.execute(action))
    }

    private suspend fun executeHubDispatch(payload: JsonObject): LocalToolExecutor.ToolResult {
        val target = payload.text("target_agent_id") ?: payload.text("targetAgentId")
        val title = payload.text("title")
        val summary = payload.text("summary")
        val instructions = payload.text("instructions")
        val expectedOutput = payload.text("expected_output") ?: payload.text("expectedOutput")
        if (listOf(target, title, summary, instructions, expectedOutput).any { it.isNullOrBlank() }) {
            return LocalToolExecutor.ToolResult(
                TOOL_HUB_DISPATCH_TASK,
                "远程任务创建失败",
                "Hub dispatch_task 缺少 target_agent_id、title、summary、instructions 或 expected_output。",
                true,
                false,
            )
        }
        val targetFact = HubRuntime.dispatchableAgents().firstOrNull { it.agentId == target }
        if (targetFact == null) {
            return LocalToolExecutor.ToolResult(
                TOOL_HUB_DISPATCH_TASK,
                "无法下发远程任务",
                "目标 Agent 不在当前可派遣路由表中，请先刷新 Hub 路由表并选择在线执行器的 agentId。",
                true,
                false,
            )
        }
        val context = taskContext()
        val actionPayload = buildJsonObject {
            put("targetAgentId", target!!)
            put("task", buildJsonObject {
                put("title", title!!)
                put("summary", summary!!)
                put("urgency", payload.text("urgency") ?: "normal")
                put("instructions", instructions!!)
                put("expectedOutput", expectedOutput!!)
            })
        }
        val idempotencyKey = buildString {
            append("dispatch:")
            append(context.sourceTurnId)
            append(':')
            append(
                MessageDigest.getInstance("SHA-256")
                    .digest(actionPayload.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                    .take(24),
            )
        }
        val result = runCatching {
            HubRuntime.submitAction(
                actionType = "dispatch_task",
                payload = actionPayload,
                turnId = context.sourceTurnId,
                conversationId = context.conversationId,
                idempotencyKey = idempotencyKey,
            )
        }.getOrElse { error ->
            return LocalToolExecutor.ToolResult(
                TOOL_HUB_DISPATCH_TASK,
                "枢卫任务下发失败",
                "Hub action 失败：${error.message ?: error.javaClass.simpleName}",
                true,
                false,
            )
        }
        if (!result.ok) {
            return LocalToolExecutor.ToolResult(
                TOOL_HUB_DISPATCH_TASK,
                "枢卫拒绝任务",
                "Hub 拒绝任务：${result.errorCode} ${result.errorMessage}",
                true,
                false,
            )
        }
        val taskId = result.result["taskId"]?.let { (it as? JsonPrimitive)?.content }
            ?: result.result["task_id"]?.let { (it as? JsonPrimitive)?.content }
            ?: "unknown"
        taskRepository?.registerHubDispatch(
            taskId = taskId,
            title = title!!,
            agentId = target!!,
            conversationId = context.conversationId,
            sourceTurnId = context.sourceTurnId,
            priority = when ((payload.text("urgency") ?: "normal").lowercase()) {
                "urgent" -> TaskPriority.URGENT
                else -> TaskPriority.NORMAL
            },
        )
        return LocalToolExecutor.ToolResult(
            TOOL_HUB_DISPATCH_TASK,
            "已交给枢卫执行 · ${taskId.takeLast(12)}",
            "Hub 已创建远程任务。task_id=$taskId，目标 Agent=$target。请告知用户任务已经交给远程 Agent，完成后会主动汇报。",
            true,
            true,
        )
    }

    private fun hubDispatchTask() = tool(
        name = TOOL_HUB_DISPATCH_TASK,
        description = "创建一个 subagent 任务并通过枢卫 Hub 交给外部执行代理。这是 Main 的核心能力：调研、编码、长耗时、多步骤或需要专门能力的任务应优先委派给 subagent。根据 Hub 路由表中的能力、类型和说明选择最合适的在线执行器；必须使用真实 target_agent_id，不得选择 Main 自身或编造 Agent。",
        required = listOf("target_agent_id", "title", "summary", "instructions", "expected_output"),
    ) {
        putJsonObject("target_agent_id") { put("type", "string") }
        putJsonObject("title") { put("type", "string") }
        putJsonObject("summary") { put("type", "string") }
        putJsonObject("instructions") { put("type", "string") }
        putJsonObject("expected_output") { put("type", "string") }
        putJsonObject("urgency") {
            put("type", "string")
            put("enum", buildJsonArray { listOf("normal", "urgent", "low").forEach { add(JsonPrimitive(it)) } })
        }
    }

    private suspend fun executeAsyncTaskTool(
        call: CloudSpeechClient.ToolCall,
        payload: JsonObject,
    ): LocalToolExecutor.ToolResult {
        val coordinator = taskCoordinator ?: return LocalToolExecutor.ToolResult(
            actionType = call.name,
            displayText = "异步任务系统不可用",
            contextText = "异步任务系统尚未初始化。",
            shouldAskLlm = true,
            success = false,
        )
        val context = taskContext()
        return when (call.name) {
            TOOL_SING_SONG -> {
                val lyrics = payload.text("lyrics")
                    ?: return LocalToolExecutor.ToolResult(call.name, "唱歌任务创建失败", "sing_song 缺少 lyrics。", true, false)
                require(lyrics.length <= 2_000) { "歌词不能超过 2000 字" }
                val title = payload.text("title") ?: "未命名歌曲"
                val taskPayload = buildJsonObject {
                    payload.forEach { (key, value) -> put(key, value) }
                    put("_silent_audio", context.silentAudio)
                }
                val task = coordinator.submit(
                    TaskSubmission(
                        taskType = SongGenerationExecutor.TYPE,
                        title = "演唱《$title》",
                        executorId = "local:song-generator",
                        executorName = "手机本地唱歌执行器",
                        conversationId = context.conversationId,
                        sourceTurnId = context.sourceTurnId,
                        priority = if (payload.text("urgent")?.toBooleanStrictOrNull() == true) TaskPriority.URGENT else TaskPriority.NORMAL,
                        inputJson = taskPayload.toString(),
                        idempotencyKey = "tool:${call.id}",
                    ),
                )
                LocalToolExecutor.ToolResult(
                    actionType = call.name,
                    displayText = "歌曲正在准备 · ${task.taskId.takeLast(8)}",
                    contextText = "唱歌任务已创建。task_id=${task.taskId}，status=${task.status.lowercase()}。请立即告诉用户：歌手正在开嗓，请稍后。不要等待歌曲生成，也不要再次调用 sing_song。",
                    shouldAskLlm = true,
                )
            }
            TOOL_TASK_STATUS -> {
                val task = payload.text("task_id")?.let { coordinator.get(it) }
                    ?: coordinator.latestActive(context.conversationId)
                    ?: return LocalToolExecutor.ToolResult(call.name, "没有找到任务", "当前会话没有匹配的异步任务。", true, false)
                LocalToolExecutor.ToolResult(
                    actionType = call.name,
                    displayText = "任务 ${task.taskId.takeLast(8)} · ${task.status.lowercase()}",
                    contextText = "task_id=${task.taskId}\ntitle=${task.title}\nexecutor=${task.executorName}\nstatus=${task.status.lowercase()}\nprogress=${task.progress}\nsummary=${task.summary}\nerror=${task.error}\noutput=${task.outputPath}",
                    shouldAskLlm = true,
                    success = task.status != "FAILED",
                )
            }
            TOOL_CANCEL_TASK -> {
                val task = payload.text("task_id")?.let { coordinator.get(it) }
                    ?: coordinator.latestActive(context.conversationId)
                    ?: return LocalToolExecutor.ToolResult(call.name, "没有可取消的任务", "当前会话没有进行中的异步任务。", true, false)
                val cancelled = coordinator.cancel(task.taskId)
                LocalToolExecutor.ToolResult(
                    actionType = call.name,
                    displayText = if (cancelled) "已取消 ${task.title}" else "任务无法取消",
                    contextText = if (cancelled) "任务 ${task.taskId} 已取消。" else "任务 ${task.taskId} 已结束或无法取消。",
                    shouldAskLlm = true,
                    success = cancelled,
                )
            }
            else -> error("unknown async task tool")
        }
    }

    fun displayName(toolName: String): String = when (toolName) {
        TOOL_MEMORY_CREATE -> "写入记忆"
        TOOL_MEMORY_SEARCH -> "查询记忆"
        TOOL_LOCATION_REFRESH -> "刷新定位"
        TOOL_LOCATION_REVERSE_GEOCODE -> "解析当前位置"
        TOOL_WEATHER_CURRENT -> "查询天气"
        TOOL_WEB_SEARCH -> "网络搜索"
        TOOL_SKILL_USE -> "使用 Skill"
        TOOL_SKILL_CREATE -> "创建 Skill"
        TOOL_SKILL_EDIT -> "编辑 Skill"
        TOOL_READ -> "读取文件"
        TOOL_WRITE -> "写入文件"
        TOOL_WORKSPACE_DELETE -> "移入回收站"
        TOOL_EXEC -> "执行命令"
        TOOL_HTTP_REQUEST -> "发送网络请求"
        TOOL_CODE_GRAPH_SEARCH -> "查询代码图谱"
        TOOL_CODE_GRAPH_EXPLAIN -> "解释代码符号"
        TOOL_SKILL_REGISTER -> "注册 Skill"
        TOOL_REQUEST_DEEP_REASONING -> "开启深度思考"
        TOOL_AGENT_SLEEP -> "进入休眠"
        TOOL_VOICE_REPLY -> "个性化播报"
        TOOL_SING_SONG -> "准备歌曲"
        TOOL_TASK_STATUS -> "查询任务"
        TOOL_CANCEL_TASK -> "取消任务"
        TOOL_HUB_DISPATCH_TASK -> "下发远程任务"
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
            TOOL_SKILL_USE -> payload.text("skill_name")
            TOOL_SKILL_CREATE, TOOL_SKILL_EDIT -> payload.text("skill_name")
            TOOL_READ -> payload.text("path")?.trimEnd('/')?.substringAfterLast('/')
                ?: (payload["paths"] as? JsonArray)?.size?.let { "$it 个项目" }
            TOOL_WRITE -> payload.text("path")?.trimEnd('/')?.substringAfterLast('/')
            TOOL_WORKSPACE_DELETE -> payload.text("path")?.trimEnd('/')?.substringAfterLast('/')
            TOOL_EXEC -> (payload["argv"] as? JsonArray)
                ?.firstOrNull()
                ?.let { it as? JsonPrimitive }
                ?.content
            TOOL_HTTP_REQUEST -> payload.text("url")
            TOOL_CODE_GRAPH_SEARCH -> payload.text("query")
            TOOL_CODE_GRAPH_EXPLAIN -> payload.text("symbol")
            TOOL_SKILL_REGISTER -> payload.text("name")
            TOOL_REQUEST_DEEP_REASONING -> "当前回合"
            TOOL_SING_SONG -> payload.text("title") ?: "未命名歌曲"
            TOOL_TASK_STATUS, TOOL_CANCEL_TASK -> payload.text("task_id")
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
            call.name != TOOL_HUB_DISPATCH_TASK &&
            call.name != TOOL_PROTOCOL_REPAIR &&
            call.name != TOOL_AGENT_SLEEP &&
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

    private fun skillUse() = tool(
        name = TOOL_SKILL_USE,
        description = "按名称加载一个已启用 Skill 的核心说明，并遵循加载结果完成当前任务。支持中文 Skill 名称。",
        required = listOf("skill_name"),
    ) {
        putJsonObject("skill_name") { put("type", "string") }
    }

    private fun skillRegister() = tool(
        name = TOOL_SKILL_REGISTER,
        description = "将已在 /workspace 中完成审查和兼容性转换的 Skill 注册到 App。调用前必须先完整读取候选目录和核心文件，确认其主要是知识或流程说明；纯脚本型、依赖 Python/Node/二进制运行时且无法改写为 read/write/exec/http_request 流程的 Skill 不得注册。注册成功后候选文件会移出工作区。",
        required = listOf("source_path", "name", "description", "core_file", "compatibility_notes", "reviewed_files"),
    ) {
        putJsonObject("source_path") {
            put("type", "string")
            put("description", "候选 Skill 在 /workspace 下的文件或目录")
        }
        putJsonObject("name") {
            put("type", "string")
            put("description", "转换后的 Skill 名称")
        }
        putJsonObject("description") {
            put("type", "string")
            put("description", "用途和触发场景简述")
        }
        putJsonObject("core_file") {
            put("type", "string")
            put("description", "相对候选目录的核心 Markdown 文件，例如 SKILL.md")
        }
        putJsonObject("compatibility_notes") {
            put("type", "string")
            put("description", "已检查的文件范围、脚本依赖以及如何适配本 App 的结论")
        }
        putJsonObject("reviewed_files") {
            put("type", "array")
            put("minItems", 1)
            put("maxItems", 50)
            put("description", "已经逐项读取或检查的候选目录相对文件路径；必须完整覆盖全部文件")
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
        description = "仅用于公开网络信息的快速查询与简短事实核实，适合一次或少量查询即可完成的即时问答。多来源调研、系统性研究、竞品分析、编码及其他重型任务不得通过本工具自行展开，应调用 hub_dispatch_task 委派给远程执行器。禁止在查询中加入私人信息。",
        required = listOf("query"),
    ) {
        putJsonObject("query") {
            put("type", "string")
            put("description", "语义完整的自然语言查询：描述要找的事实或理想页面，并包含必要的实体、领域、地区和时间范围；不要只堆砌宽泛关键词")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 10)
        }
    }

    private fun reasoningEscalation() = tool(
        name = TOOL_REQUEST_DEEP_REASONING,
        description = "为当前用户回合申请一次深度思考，仅用于能在当前回合快速完成的复杂本地分析、方案权衡、排障或头脑风暴。深度思考不能代替任务委派；调研、编码、长耗时、多步骤或需要专门能力的任务应优先调用 hub_dispatch_task。简单问答不得使用。",
        required = listOf("reason"),
    ) {
        putJsonObject("reason") {
            put("type", "string")
            put("description", "需要深度思考的简短原因，不向用户播报")
        }
    }

    private fun voiceReply() = tool(
        name = TOOL_VOICE_REPLY,
        description = "终止型个性化语音回复。仅在用户明确要求临时更换音色、模仿特殊说话风格或设计新音色时使用。只支持讲话，不支持唱歌；唱歌必须调用 sing_song。调用即作为本回合最终答复，普通正文必须为空。",
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
        putJsonObject("style_prompt") {
            put("type", "string")
            put("description", "preset 模式的语气、节奏、情绪要求")
        }
        putJsonObject("voice_prompt") {
            put("type", "string")
            put("description", "design 模式必填，描述目标音色、年龄感、音调和说话风格")
        }
    }

    private fun singSong() = tool(
        name = TOOL_SING_SONG,
        description = "创建异步唱歌任务。仅当用户当前消息明确要求唱歌或演唱时调用。工具会立即返回 task_id，不等待音频生成；收到成功结果后告诉用户‘歌手正在开嗓，请稍后’，然后正常结束当前回合。朗诵歌词、普通配音或背景音乐请求不得调用。",
        required = listOf("lyrics"),
    ) {
        putJsonObject("lyrics") {
            put("type", "string")
            put("description", "需要演唱的完整歌词，最多 2000 字")
        }
        putJsonObject("voice") {
            put("type", "string")
            put("enum", buildJsonArray { listOf("冰糖", "茉莉", "苏打", "白桦").forEach { add(JsonPrimitive(it)) } })
        }
        putJsonObject("style_prompt") {
            put("type", "string")
            put("description", "曲风、节奏和情绪要求")
        }
        putJsonObject("title") {
            put("type", "string")
            put("description", "歌曲标题")
        }
        putJsonObject("urgent") {
            put("type", "boolean")
            put("description", "仅当用户明确要求立即处理时为 true；不能用于绕过用户说话保护")
        }
    }

    private fun taskStatus() = tool(
        name = TOOL_TASK_STATUS,
        description = "查询异步任务状态。用户询问刚才的歌曲或后台任务进度时使用；省略 task_id 时查询当前会话最新的进行中任务。",
    ) {
        putJsonObject("task_id") { put("type", "string") }
    }

    private fun cancelTask() = tool(
        name = TOOL_CANCEL_TASK,
        description = "取消进行中的异步任务。仅在用户明确表示不再需要该任务或要求取消时使用；省略 task_id 时取消当前会话最新的进行中任务。",
    ) {
        putJsonObject("task_id") { put("type", "string") }
    }

    private fun agentSleep() = tool(
        name = TOOL_AGENT_SLEEP,
        description = "终止型休眠操作。仅当用户当前明确要求助手离开、结束交互或进入休眠时调用，例如‘退下吧’、‘没事了’、‘休眠’、‘你走吧’、‘再见’、‘滚蛋’及语义明确的同类表达。用户只是讨论、引用、询问这些词语，或意图不明确时不得调用。调用时正文必须为空，必须单独调用，成功后 App 立即执行完整休眠流程。",
    ) {}

    private fun readFile() = tool(
        name = TOOL_READ,
        description = "读取虚拟文件系统中的一个或多个文本文件，也可列出目录。只允许 /source、/logs 和 /workspace；Skill 文件必须使用 skill_use。日志优先使用 tail_lines，大文件使用 offset 和 limit 分段读取。",
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
        putJsonObject("log_levels") {
            put("type", "array")
            put("description", "仅用于 /logs，按级别筛选；可选 V、D、I、W、E")
            put("items", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { listOf("V", "D", "I", "W", "E").forEach { add(JsonPrimitive(it)) } })
            })
        }
        putJsonObject("log_tags") {
            put("type", "array")
            put("description", "仅用于 /logs，按分类标签筛选，例如 AGENT、TOOL、LLM、ASR、TTS、AUDIO、TASK、NETWORK、UI；VA_ 前缀可省略")
            put("items", buildJsonObject { put("type", "string") })
        }
        putJsonObject("event_prefixes") {
            put("type", "array")
            put("description", "仅用于 /logs，按事件名前缀筛选，例如 audio.route、agent.tool")
            put("items", buildJsonObject { put("type", "string") })
        }
        putJsonObject("query") {
            put("type", "string")
            put("description", "仅用于 /logs，在完整日志条目中进行不区分大小写的关键词筛选")
        }
    }

    private fun writeFile() = tool(
        name = TOOL_WRITE,
        description = "在 /workspace 中创建、覆盖、追加或按行补丁 UTF-8 文本文件。不得写入源码、日志或 Skill 目录；单次 content 最多 8 KiB。",
        required = listOf("path", "content"),
    ) {
        putJsonObject("path") {
            put("type", "string")
            put("description", "必须位于 /workspace 的绝对虚拟路径；patch 前先 read 获取文件 sha256")
        }
        putJsonObject("content") {
            put("type", "string")
            put("description", "写入文本；单次最多 8 KiB。大文本请拆分为 append，多数已有文件复制请用 exec 的 cp argv")
        }
        putJsonObject("mode") {
            put("type", "string")
            put("enum", buildJsonArray {
                add(JsonPrimitive("overwrite"))
                add(JsonPrimitive("append"))
                add(JsonPrimitive("create"))
                add(JsonPrimitive("patch"))
            })
        }
        putJsonObject("start_line") {
            put("type", "integer")
            put("minimum", 1)
            put("description", "仅 patch：要替换的起始行（含）")
        }
        putJsonObject("end_line") {
            put("type", "integer")
            put("minimum", 1)
            put("description", "仅 patch：要替换的结束行（含）")
        }
        putJsonObject("expected_sha256") {
            put("type", "string")
            put("description", "可选；patch 前读取到的文件 sha256，不一致时拒绝修改")
        }
    }

    private fun workspaceDelete() = tool(
        name = TOOL_WORKSPACE_DELETE,
        description = "删除 /workspace 中的文件或目录。Agent 删除内容时必须使用本工具，内容会移入用户可恢复的回收站并保留 30 天；不要通过 exec 的 rm、rmdir、unlink 或 find -delete 绕过回收站。",
        required = listOf("path"),
    ) {
        putJsonObject("path") {
            put("type", "string")
            put("description", "需要删除的 /workspace 绝对虚拟路径；不能删除工作区根目录")
        }
    }

    private fun execCommand() = tool(
        name = TOOL_EXEC,
        description = "在 App 自身 UID 沙箱中执行一次性程序。使用 cwd 和 argv；文件路径只允许 /source、/logs 或 /workspace。无 shell、无环境变量、无管道语法；不能访问 Skill 目录。",
        required = listOf("argv"),
    ) {
        putJsonObject("argv") {
            put("type", "array")
            put("description", "程序和参数数组。例如 [\"cp\", \"/source/README.md\", \"/workspace/README.md\"]。不要传 shell command 字符串。")
            put("items", buildJsonObject { put("type", "string") })
        }
        putJsonObject("timeout_seconds") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 120)
        }
        putJsonObject("cwd") {
            put("type", "string")
            put("description", "虚拟工作目录，默认 /workspace；只能是 /source、/logs 或 /workspace 下的绝对虚拟路径")
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
        const val SYSTEM_SKILL_LOCAL_EXECUTION = "local-execution"
        const val SYSTEM_SKILL_ADVANCED_TTS = "advanced-tts-directing"
        const val TOOL_MEMORY_CREATE = "memory_create"
        const val TOOL_MEMORY_SEARCH = "memory_search"
        const val TOOL_LOCATION_REFRESH = "location_refresh"
        const val TOOL_LOCATION_REVERSE_GEOCODE = "location_reverse_geocode"
        const val TOOL_WEATHER_CURRENT = "weather_get_current"
        const val TOOL_WEB_SEARCH = "web_search"
        const val TOOL_READ = "read"
        const val TOOL_WRITE = "write"
        const val TOOL_WORKSPACE_DELETE = "workspace_delete"
        const val TOOL_EXEC = "exec"
        const val TOOL_HTTP_REQUEST = "http_request"
        const val TOOL_CODE_GRAPH_SEARCH = "code_graph_search"
        const val TOOL_CODE_GRAPH_EXPLAIN = "code_graph_explain"
        const val TOOL_SKILL_REGISTER = "skill_register"
        const val TOOL_SKILL_USE = "skill_use"
        const val TOOL_SKILL_CREATE = "skill_create"
        const val TOOL_SKILL_EDIT = "skill_edit"
        const val TOOL_REQUEST_DEEP_REASONING = "request_deep_reasoning"
        const val TOOL_AGENT_SLEEP = "agent_sleep"
        const val TOOL_VOICE_REPLY = "voice_reply"
        const val TOOL_SING_SONG = "sing_song"
        const val TOOL_TASK_STATUS = "task_status"
        const val TOOL_CANCEL_TASK = "cancel_task"
        const val TOOL_HUB_DISPATCH_TASK = "hub_dispatch_task"
        const val TOOL_PROTOCOL_REPAIR = "__repair_tool_protocol"

        val NATIVE_TOOL_NAMES = setOf(
            TOOL_MEMORY_CREATE, TOOL_MEMORY_SEARCH, TOOL_AGENT_SLEEP, TOOL_HUB_DISPATCH_TASK,
            TOOL_REQUEST_DEEP_REASONING, TOOL_SKILL_USE, TOOL_WEB_SEARCH,
        )
        val LOCAL_EXECUTION_TOOL_NAMES = setOf(
            TOOL_READ, TOOL_WRITE, TOOL_WORKSPACE_DELETE, TOOL_EXEC, TOOL_HTTP_REQUEST,
            TOOL_CODE_GRAPH_SEARCH, TOOL_CODE_GRAPH_EXPLAIN, TOOL_SKILL_CREATE, TOOL_SKILL_EDIT,
        )
        val HIDDEN_TOOL_NAMES = LOCAL_EXECUTION_TOOL_NAMES + TOOL_VOICE_REPLY

        private const val MAX_DISPLAY_SUMMARY_CHARS = 48

        private val PARALLEL_SAFE_TOOL_NAMES = setOf(
            TOOL_MEMORY_SEARCH,
            TOOL_WEB_SEARCH,
            TOOL_READ,
            TOOL_CODE_GRAPH_SEARCH,
            TOOL_CODE_GRAPH_EXPLAIN,
        )

        private val ASYNC_TASK_TOOL_NAMES = setOf(TOOL_SING_SONG, TOOL_TASK_STATUS, TOOL_CANCEL_TASK)

    }
}
