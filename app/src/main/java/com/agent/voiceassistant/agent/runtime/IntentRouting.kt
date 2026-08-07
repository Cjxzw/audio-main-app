package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class IntentCategory { CHAT, QUICK_ANSWER, TASK }

data class IntentRoutingResult(
    val category: IntentCategory,
    val complexity: String,
    val delegate: Boolean,
    val agentId: String?,
    val reason: String,
    val confidence: Double,
)

data class ToolGateDecision(
    val blockedCallIds: Set<String> = emptySet(),
    val prompt: String? = null,
)

object IntentRoutingParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): Result<IntentRoutingResult> = runCatching {
        val root = json.parseToJsonElement(raw.trim()) as? JsonObject
            ?: error("路由结果不是 JSON 对象")
        val category = when ((root["category"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
            "chat" -> IntentCategory.CHAT
            "quick_answer", "quick-answer", "quick" -> IntentCategory.QUICK_ANSWER
            "task" -> IntentCategory.TASK
            else -> error("路由 category 无效")
        }
        val complexity = (root["complexity"] as? JsonPrimitive)?.contentOrNull
            ?.lowercase()
            ?.takeIf { it in setOf("low", "medium", "high") }
            ?: "medium"
        val delegate = (root["delegate"] as? JsonPrimitive)?.contentOrNull
            ?.toBooleanStrictOrNull()
            ?: false
        val agentId = (root["agent_id"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val reason = (root["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().take(500)
        val confidence = (root["confidence"] as? JsonPrimitive)?.contentOrNull
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 1.0)
            ?: 0.0
        IntentRoutingResult(category, complexity, delegate, agentId, reason, confidence)
    }
}

object IntentRoutingPrompt {
    val SYSTEM = """
你是 Hanwo 的意图路由器，不是主助手，不执行任务，也不向用户回复。
只判断最后一条用户消息的意图和执行路径；历史仅用于理解“继续”“刚才”“这个”等指代、当前任务阶段和已经完成的委派。
闲聊输出 category=chat；单轮简单事实或直接回答输出 category=quick_answer；需要本地文件、命令、网络、多步骤处理、编码、研究、持续跟进或专门执行者的请求输出 category=task。
task 必须判断是否应委派。若当前路由表存在合适的在线执行者，delegate=true 并填写真实 agent_id；不得编造 agent_id，也不得把 Main 自身作为执行者。
只输出一个严格 JSON 对象，不要 Markdown、代码围栏、解释、工具调用或思考文本。
字段必须是：category(chat|quick_answer|task)、complexity(low|medium|high)、delegate(boolean)、agent_id(string|null)、reason(string)、confidence(number 0 到 1)。
""".trimIndent()

    fun userContent(routeTable: String): String = buildString {
        appendLine("当前有效 Hub 路由表：")
        appendLine(routeTable.ifBlank { "当前没有可用远程执行者。" })
        appendLine()
        appendLine("只对最后一条用户消息作出判断。")
    }
}

fun IntentRoutingResult.toSystemPrompt(validAgentIds: Set<String>): String? {
    if (category != IntentCategory.TASK || !delegate) return null
    val target = agentId?.takeIf { it in validAgentIds } ?: return null
    return "意图路由已判定当前请求为复杂任务，建议立即委派给 $target。下一次模型请求必须优先调用 hub_dispatch_task，目标 target_agent_id=$target；不要继续扩展本地业务工具。路由原因：${reason.ifBlank { "需要专门执行者" }}"
}

fun List<CloudSpeechClient.LlmMessage>.withoutPrivateReasoning(): List<CloudSpeechClient.LlmMessage> =
    map { message -> message.copy(reasoningContent = null, responseMetadata = null, imageInputs = emptyList()) }
