package com.agent.voiceassistant.tools

import com.agent.voiceassistant.agent.AgentAction
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.hub.HubRuntime
import com.agent.voiceassistant.tasks.AsyncTaskCoordinator
import com.agent.voiceassistant.tasks.TaskPriority
import com.agent.voiceassistant.tasks.TaskRepository
import com.agent.voiceassistant.tools.runtime.RuntimeCommandRegistry
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
import java.util.concurrent.atomic.AtomicInteger

class MainToolRegistry(
    private val executor: LocalToolExecutor,
    private val taskCoordinator: AsyncTaskCoordinator? = null,
    private val taskRepository: TaskRepository? = null,
    private val taskContext: () -> TaskToolContext = { TaskToolContext("default", "") },
    private val historicalConversationSearch: ((String, Int) -> String)? = null,
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
        REALTIME,
    }

    data class Execution(
        val call: CloudSpeechClient.ToolCall,
        val result: LocalToolExecutor.ToolResult,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val historicalSearchCount = AtomicInteger(0)

    fun resetTurnToolBudget() {
        historicalSearchCount.set(0)
    }

    fun definitions(
        profile: Profile = Profile.STANDALONE,
        allowReasoningEscalation: Boolean,
    ): List<CloudSpeechClient.ToolDefinition> {
        if (profile == Profile.REALTIME) {
            return listOf(mainConversationQuery(), memorySearch(), delegateToMain(), realtimeHangup())
        }
        return listOf(
            memoryEdit(),
            memorySearch(),
            webSearch(),
            readFile(),
            writeFile(),
            workspaceDelete(),
            httpRequest(),
            runCommand(),
            describeCredentials(),
            skillRegister(),
            skillEdit(),
            skillUse(),
            taskStatus(),
            cancelTask(),
            hubDispatchTask(),
        )
    }

    fun isReasoningEscalation(call: CloudSpeechClient.ToolCall): Boolean = false

    fun isTerminalPresentation(call: CloudSpeechClient.ToolCall): Boolean = false

    fun isSkillRead(call: CloudSpeechClient.ToolCall): Boolean =
        call.name == TOOL_SKILL_USE && parseArguments(call.arguments).text("mode")?.lowercase() != "run"

    private val nativeToolNames by lazy {
        Profile.entries.flatMap { definitions(it, false) }.mapTo(hashSetOf()) { it.name }
    }

    fun isNativeTool(name: String): Boolean = name in nativeToolNames

    fun shouldPersistToolCall(call: CloudSpeechClient.ToolCall): Boolean =
        isNativeTool(call.name) && !isSkillRead(call) && !isReasoningEscalation(call)

    fun reasoningEscalationReason(call: CloudSpeechClient.ToolCall): String =
        (parseArguments(call.arguments)["reason"] as? JsonPrimitive)
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(200)
            ?: "模型判断当前问题需要深入分析"

    fun canExecuteInParallel(call: CloudSpeechClient.ToolCall): Boolean = when (call.name) {
        TOOL_HTTP_REQUEST -> false
        TOOL_RUN -> false
        else -> call.name in PARALLEL_SAFE_TOOL_NAMES
    }

    suspend fun execute(call: CloudSpeechClient.ToolCall): Execution {
        val payload = parseArguments(call.arguments)
        if (call.name == TOOL_MEMORY_SEARCH &&
            (payload.text("scope") == "history" || payload.text("conversation_search_mode") == "all_history")
        ) {
            val query = payload.text("query").orEmpty().trim()
            if (query.isBlank()) {
                return Execution(call, LocalToolExecutor.ToolResult(call.name, "历史会话搜索失败", "必须提供一个关键词。", shouldAskLlm = false, success = false))
            }
            if (query.length > 10) {
                return Execution(call, LocalToolExecutor.ToolResult(call.name, "历史会话搜索失败", "关键词最多 10 个字符。", shouldAskLlm = false, success = false))
            }
            if (historicalSearchCount.get() >= 3) {
                return Execution(call, LocalToolExecutor.ToolResult(call.name, "历史会话搜索已限流", "每个回合最多搜索 3 次，请合并关键词后再试。", shouldAskLlm = false))
            }
            val result = historicalConversationSearch?.invoke(query, 3) ?: "历史会话搜索暂不可用。"
            val missed = result.startsWith("未命中") || result.startsWith("没有找到")
            if (!missed) historicalSearchCount.incrementAndGet()
            return Execution(call, LocalToolExecutor.ToolResult(call.name, "历史会话搜索", result, shouldAskLlm = false, success = !missed))
        }
        if (call.name == TOOL_HUB_DISPATCH_TASK) {
            return Execution(call, executeHubDispatch(payload))
        }
        if (call.name in ASYNC_TASK_TOOL_NAMES) {
            return Execution(call, executeAsyncTaskTool(call, payload))
        }
        val legacyAction = when (call.name) {
            TOOL_MEMORY_EDIT -> "memory.edit"
            TOOL_MEMORY_SEARCH -> "memory.search"
            TOOL_WEB_SEARCH -> "web.search"
            TOOL_SKILL_USE -> "skill.use"
            TOOL_READ -> "read"
            TOOL_WRITE -> "write"
            TOOL_WORKSPACE_DELETE -> "delete"
            TOOL_HTTP_REQUEST -> "http_request"
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
        TOOL_MEMORY_EDIT -> "编辑记忆"
        TOOL_MEMORY_SEARCH -> "查询记忆"
        TOOL_WEB_SEARCH -> "网络搜索"
        TOOL_SKILL_USE -> "使用 Skill"
        TOOL_SKILL_CREATE -> "创建 Skill"
        TOOL_SKILL_EDIT -> "编辑 Skill"
        TOOL_READ -> "读取文件"
        TOOL_WRITE -> "写入文件"
        TOOL_WORKSPACE_DELETE -> "移入回收站"
        TOOL_RUN -> "运行受控命令"
        TOOL_HTTP_REQUEST -> "发送网络请求"
        TOOL_DESCRIBE_CREDENTIALS -> "查询凭证"
        TOOL_SKILL_REGISTER -> "注册 Skill"
        TOOL_TASK_STATUS -> "查询任务"
        TOOL_CANCEL_TASK -> "取消任务"
        TOOL_HUB_DISPATCH_TASK -> "下发远程任务"
        TOOL_MAIN_CONVERSATION_QUERY -> "查询主会话"
        TOOL_DELEGATE_TO_MAIN -> "转交主会话"
        TOOL_PROTOCOL_REPAIR -> "修正工具调用格式"
        else -> toolName
    }

    fun displaySummary(call: CloudSpeechClient.ToolCall): String? {
        val payload = parseArguments(call.arguments)
        val value = when (call.name) {
            TOOL_MEMORY_EDIT -> payload.text("memory_id")?.let { "${payload.text("mode") ?: "编辑"} ${it.take(12)}" }
            TOOL_MEMORY_SEARCH -> payload.text("query")
            TOOL_WEB_SEARCH -> payload.text("query")
            TOOL_SKILL_USE -> payload.text("skill_name")
            TOOL_SKILL_CREATE, TOOL_SKILL_EDIT -> payload.text("skill_name")
            TOOL_READ -> payload.text("path")?.trimEnd('/')?.substringAfterLast('/')
                ?: (payload["paths"] as? JsonArray)?.size?.let { "$it 个项目" }
            TOOL_WRITE -> payload.text("path")?.trimEnd('/')?.substringAfterLast('/')
            TOOL_WORKSPACE_DELETE -> payload.text("path")?.trimEnd('/')?.substringAfterLast('/')
            TOOL_RUN -> payload.text("command")
            TOOL_HTTP_REQUEST -> payload.text("download_to") ?: payload.text("url")
            TOOL_SKILL_REGISTER -> payload.text("name")
            TOOL_TASK_STATUS, TOOL_CANCEL_TASK -> payload.text("task_id")
            TOOL_MAIN_CONVERSATION_QUERY -> payload.text("keyword") ?: "最近对话"
            TOOL_DELEGATE_TO_MAIN -> payload.text("content")
            else -> null
        }
        return value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.length <= MAX_DISPLAY_SUMMARY_CHARS) it else it.take(MAX_DISPLAY_SUMMARY_CHARS - 3) + "..." }
    }

    fun countsTowardAutomaticReasoning(call: CloudSpeechClient.ToolCall): Boolean =
            call.name != TOOL_HUB_DISPATCH_TASK &&
            call.name != TOOL_PROTOCOL_REPAIR

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

    private fun memoryEdit() = tool(
        name = TOOL_MEMORY_EDIT,
        description = "编辑或删除本地长期记忆。必须使用 memory_search 返回的 UID 精确定位；edit 模式覆写已有记忆并刷新更新时间，memory_id=new 才表示新增。delete 模式删除已有记忆，不允许使用 new。LLM 自动编辑不会获得手动加分，只有用户在 UI 中手动编辑才获得一次永久手动加分。",
        required = listOf("mode", "memory_id"),
    ) {
        putJsonObject("mode") {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive("edit")); add(JsonPrimitive("delete")) })
            put("description", "edit 覆写或新增；delete 删除已有记忆")
        }
        putJsonObject("memory_id") {
            put("type", "string")
            put("description", "已有记忆 UID；仅 edit 模式允许填写 new")
        }
        putJsonObject("content") {
            put("type", "string")
            put("description", "edit 模式下要保存的完整记忆内容")
        }
        putJsonObject("tags") {
            put("type", "array")
            put("description", "edit 模式下替换为新的标签列表")
            put("items", buildJsonObject { put("type", "string") })
        }
    }

    private fun skillUse() = tool(
        name = TOOL_SKILL_USE,
        description = "按名称加载或运行一个已启用 Skill。默认 mode=load，只读取 SKILL.md 或指定文本资源；mode=run 时必须明确指定 Skill 声明的 QuickJS entry。脚本通过 hanwo.同名命令(args) 使用白名单运行时命令，不启动系统 Shell。",
        required = listOf("skill_name"),
    ) {
        putJsonObject("skill_name") { put("type", "string") }
        putJsonObject("mode") {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive("load")); add(JsonPrimitive("run")) })
            put("description", "load 读取资源；run 执行 Skill 声明的 QuickJS entry")
        }
        putJsonObject("resource_name") {
            put("type", "string")
            put("description", "Skill 内相对路径；run 模式必须匹配 SKILL.md 的 entry，例如 main.js")
        }
        putJsonObject("input") {
            put("type", "string")
            put("description", "run 模式传给 QuickJS main(input) 的 JSON 字符串")
        }
        putJsonObject("timeout_seconds") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 120)
        }
    }

    private fun skillRegister() = tool(
        name = TOOL_SKILL_REGISTER,
        description = "将 /workspace 中准备好的 Skill 候选提交注册请求。Skill 可以包含 SKILL.md、文本资源和 QuickJS entry；运行时、入口和权限会在审批摘要中记录，注册或变更必须等待用户在聊天中批准后才生效。已有待处理事项时不得创建新的注册或变更请求。",
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

    private fun skillEdit() = tool(
        name = TOOL_SKILL_EDIT,
        description = "提交已注册 Skill 的变更候选，用户在聊天中批准后才生效，无需手动删除旧 Skill。可替换全文、局部文本、新增或删除资源、改名，或使用 replace_from_workspace 发布完整候选目录。已有唯一待办时系统拒绝任何新请求。修改前用 skill_use 读取资源与 SHA-256。",
        required = listOf("skill_name", "operation", "expected_sha256"),
    ) {
            listOf("skill_name", "resource_name", "old_text", "source_path", "name", "description").forEach { key ->
            putJsonObject(key) { put("type", "string") }
        }
            putJsonObject("expected_sha256") {
                put("type", "string")
                put("description", "rename/create_resource/replace_from_workspace 使用 skill_use 返回的整个 Skill version；replace_text/replace_resource/delete_resource 使用目标资源 sha256")
            }
            putJsonObject("new_text") {
                put("type", "string")
                put("description", "rename 时是新名称；create_resource/replace_resource 时是完整文件内容；replace_text 时是替换文本")
            }
        putJsonObject("operation") {
            put("type", "string")
            put("enum", buildJsonArray {
                listOf("rename", "create_resource", "replace_text", "replace_resource", "delete_resource", "replace_from_workspace").forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    private fun memorySearch() = tool(
        name = TOOL_MEMORY_SEARCH,
        description = "查询本地长期记忆或历史会话。长期记忆搜索使用不区分大小写的连续字面子串匹配，不支持语义相似、同义词或跳跃字符；结果包含稳定 UID、分数和更新时间，命中会增加有限召回分。需要补充旧会话细节时，将 scope 设为 history 或 conversation_search_mode 设为 all_history，并提供不超过 10 个字符的关键词。历史会话搜索每回合最多 3 次，每次最多返回 3 轮。",
    ) {
        putJsonObject("query") {
            put("type", "string")
            put("description", "长期记忆使用实际出现在正文或标签中的连续关键词；为空时按评分返回常用记忆")
        }
        putJsonObject("scope") {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive("memory")); add(JsonPrimitive("history")) })
            put("description", "memory 查询长期记忆；history 查询历史会话细节")
        }
        putJsonObject("conversation_search_mode") {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive("none")); add(JsonPrimitive("all_history")) })
            put("description", "设为 all_history 时在全部历史标准会话中搜索")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 10)
        }
    }

    private fun mainConversationQuery() = tool(
        name = TOOL_MAIN_CONVERSATION_QUERY,
        description = "查询主会话最近正文和任务进度。只返回用户正文、助手最终正文以及简要工具调用/结果，不返回思考内容或工具原始参数。需要理解主会话后台任务时使用。",
    ) {
        putJsonObject("keyword") {
            put("type", "string")
            put("description", "可选关键词；为空时查询最近几轮")
        }
        putJsonObject("recent_turns") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 10)
        }
    }

    private fun delegateToMain() = tool(
        name = TOOL_DELEGATE_TO_MAIN,
        description = "异步把一段用户意图提交给主会话处理。调用会立即返回受理状态，不等待任务完成；主会话完成后系统会把结果送回当前 Realtime 通话。主会话忙碌时可能返回繁忙。不能直接委派到远端。",
        required = listOf("content"),
    ) {
        putJsonObject("content") {
            put("type", "string")
            put("description", "要插入主会话的完整内容")
        }
    }

    private fun realtimeHangup() = tool(
        name = TOOL_REALTIME_HANGUP,
        description = "主动挂断当前 Realtime 实时语音通话。仅当用户明确要求结束通话、挂断或离开时调用；调用后立即关闭 Realtime 音频管线。",
    ) {}

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

    private fun readFile() = tool(
        name = TOOL_READ,
        description = "读取虚拟文件系统中的文本或二进制文件，encoding 默认 utf8。base64/hex 使用 byte_offset（从 0 开始）和 byte_limit 分页，不能混用行参数；二进制每次一个文件、上限 8 MiB。只允许 /source、/logs 和 /workspace；目录列出和文件查找请使用 run。日志优先使用 tail_lines，大文件使用 offset 和 limit 分段读取。",
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
        putJsonObject("encoding") { put("type", "string"); put("enum", buildJsonArray { listOf("utf8", "base64", "hex").forEach { add(JsonPrimitive(it)) } }) }
        putJsonObject("byte_offset") { put("type", "integer"); put("minimum", 0); put("description", "二进制起始字节，从 0 开始；续读使用 next_byte_offset") }
        putJsonObject("byte_limit") { put("type", "integer"); put("minimum", 1); put("description", "base64 最多 28000 字节，hex 最多 18000 字节") }
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
        description = "在 /workspace 中创建、覆盖、追加或按行补丁文件。encoding 默认 utf8，也可 base64/hex 解码落盘（8 MiB 上限，不能 patch）。凭证引用不能写入文件，应经 Skill input 交给 http_request。大文本或诊断报告应优先分段写入：先 overwrite/create 首段，再用 append 续写；每段建议最多 50 行且不超过 8 KiB。即使一次写入超过建议值，工具仍会完成写入并返回警告；不得写入源码、日志或 Skill 目录。",
        required = listOf("path", "content"),
    ) {
        putJsonObject("path") {
            put("type", "string")
            put("description", "必须位于 /workspace 的绝对虚拟路径；patch 前先 read 获取文件 sha256")
        }
        putJsonObject("content") {
            put("type", "string")
            put("description", "写入文本。最佳实践是每段最多 50 行且不超过 8 KiB：第一段用 overwrite（新文件可用 create），后续用 append；超过建议值仍会写入成功，但结果会给出警告。")
        }
        putJsonObject("encoding") { put("type", "string"); put("enum", buildJsonArray { listOf("utf8", "base64", "hex").forEach { add(JsonPrimitive(it)) } }) }
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
        description = "删除 /workspace 中的文件或目录。Agent 删除内容时必须使用本工具，内容会移入用户可恢复的回收站并保留 30 天；不要绕过回收站。",
        required = listOf("path"),
    ) {
        putJsonObject("path") {
            put("type", "string")
            put("description", "需要删除的 /workspace 绝对虚拟路径；不能删除工作区根目录")
        }
    }

    private fun runCommand() = tool(
        name = TOOL_RUN,
        description = "执行一个 App 内置的受控运行时命令。只能传 command 和 JSON args，不能传 Shell、程序路径、管道、脚本或任意系统命令。复杂流程应注册为 QuickJS Skill。",
        required = listOf("command"),
    ) {
        putJsonObject("command") {
            put("type", "string")
            put("enum", buildJsonArray {
                RuntimeCommandRegistry.RUN_COMMANDS
                    .forEach { add(JsonPrimitive(it)) }
            })
        }
        putJsonObject("args") {
            put("type", "object")
            put("description", "list: path/offset/limit；find: path/query（字面子串，不支持通配符）/max_results/max_depth；grep: path 或 paths、pattern（字面匹配）、ignore_case、max_results；stat/wc: path；sha256: value 或 path 二选一（按原始字节计算文件摘要）。参数可原样传给 Skill 中的 hanwo.同名命令(args)。")
        }
    }

    private fun describeCredentials() = tool(
        name = TOOL_DESCRIBE_CREDENTIALS,
        description = "查找可用凭证，只返回名称、键名、稳定引用、允许 URL 和到期时间，不返回真实值。用户消息已有引用可直接用于顶层 http_request 或 QuickJS Skill 的 http_request，无需重复查询。",
    ) {
        putJsonObject("query") { put("type", "string"); put("description", "按凭证名称、UID 或键名过滤，空白列出前若干条") }
        putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 20) }
    }

    private fun httpRequest() = tool(
        name = TOOL_HTTP_REQUEST,
        description = "发送完整 HTTP/HTTPS 请求，用于 API 调试、纯文本 Skill 调用和文件下载。Skill 必须提供完整 URL、headers 和 body；凭证在 Header 或 body 中原样使用 {{cred:UID.字段UID}}，由执行器检查 URL 白名单后还原；不需要 credential_profile。兼容旧版 profile 与 {{credential.profile.key}}。禁止将凭证放入 URL。大响应会自动缓存，长期保存请使用 download_to。",
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
        putJsonObject("headers") {
            put("type", "object")
            put("additionalProperties", buildJsonObject { put("type", "string") })
            put("description", "完整请求 Header；敏感值使用 describe_credentials 或用户消息提供的凭证引用")
        }
        putJsonObject("timeout_ms") {
            put("type", "integer")
            put("minimum", 1000)
            put("maximum", 120000)
            put("description", "单次请求超时毫秒数，默认 5000；POST 等写入请求不自动重试")
        }
        putJsonObject("credential_profile") {
            put("type", "string")
            put("description", "Android Keystore 中已配置的凭据 profile；请求 URL 必须匹配其允许前缀")
        }
        putJsonObject("download_to") {
            put("type", "string")
            put("description", "可选的持久下载路径，只能是 /workspace 下的虚拟路径。指定后响应会以文件流保存，不把正文放入模型上下文；单文件上限 128 MiB。未指定时，超过展示限制的响应会自动保存到临时 HTTP cache，三天后清理。")
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
        const val TOOL_MEMORY_EDIT = "memory_edit"
        const val TOOL_MEMORY_SEARCH = "memory_search"
        const val TOOL_WEB_SEARCH = "web_search"
        const val TOOL_READ = "read"
        const val TOOL_WRITE = "write"
        const val TOOL_WORKSPACE_DELETE = "delete"
        const val TOOL_RUN = "run"
        const val TOOL_DESCRIBE_CREDENTIALS = "describe_credentials"
        const val TOOL_HTTP_REQUEST = "http_request"
        const val TOOL_SKILL_REGISTER = "skill_register"
        const val TOOL_SKILL_USE = "skill_use"
        const val TOOL_SKILL_CREATE = "skill_create"
        const val TOOL_SKILL_EDIT = "skill_edit"
        const val TOOL_TASK_STATUS = "task_status"
        const val TOOL_CANCEL_TASK = "cancel_task"
        const val TOOL_HUB_DISPATCH_TASK = "hub_dispatch_task"
        const val TOOL_PROTOCOL_REPAIR = "__repair_tool_protocol"
        const val TOOL_MAIN_CONVERSATION_QUERY = "main_conversation_query"
        const val TOOL_DELEGATE_TO_MAIN = "delegate_to_main"
        const val TOOL_REALTIME_HANGUP = "realtime_hangup"

        private const val MAX_DISPLAY_SUMMARY_CHARS = 48

        private val PARALLEL_SAFE_TOOL_NAMES = setOf(
            TOOL_MEMORY_SEARCH,
            TOOL_WEB_SEARCH,
            TOOL_READ,
        )

        private val ASYNC_TASK_TOOL_NAMES = setOf(TOOL_TASK_STATUS, TOOL_CANCEL_TASK)

    }
}
