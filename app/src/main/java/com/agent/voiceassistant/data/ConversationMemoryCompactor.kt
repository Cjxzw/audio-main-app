package com.agent.voiceassistant.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class ConversationMemoryCompactor {
    fun instruction(
        source: ConversationCompressionSource,
        existingMemories: List<StoredMemory>,
    ): String = buildString {
        appendLine("这是后台长期记忆提炼任务，不是用户的新请求。前面的消息是会话快照；不得向用户答复，不得调用工具。")
        appendLine("请先在内部完整思考，再只输出一个合法 JSON 对象。JSON 对象必须严格匹配下面的结构，不能使用 Markdown 代码围栏或附加解释。")
        appendLine("结构：")
        appendLine("{\"memories\":[{\"content\":\"独立、明确、可跨会话使用的事实\",\"category\":\"preference|profile|ongoing|decision|recurring_topic|explicit\",\"tags\":[\"短标签\"],\"evidence_message_ids\":[\"真实用户消息 ID\"],\"existing_memory_id\":\"可选的已有记忆 ID\"}]}")
        appendLine()
        appendLine("判断规则：")
        appendLine("1. 保留用户明确要求记住的信息，以及稳定身份、偏好、习惯、禁忌、交流方式、长期目标、进行中的项目、已确认决定、持续约束、反复话题和用户对助手的纠正。")
        appendLine("2. 忽略寒暄、一次性查询、临时地点、短期情绪、已过期状态、工具结果、助手未经用户确认的推测、API Key、令牌、密码和精确定位等敏感值。")
        appendLine("3. 只能把用户消息作为事实证据。每项至少引用一个下面列出的真实用户消息 ID；不允许编造 ID。")
        appendLine("4. 一条 memory 只表达一个可独立复用的事实。不要保存对话摘要，不要把多个无关事实拼成一条。")
        appendLine("5. 若内容与已有记忆相同、修正或可合并，必须填写 existing_memory_id；否则省略该字段。")
        appendLine("6. 没有值得长期保存的信息时，必须返回 {\"memories\":[]}，这属于合法成功结果。")
        appendLine()
        appendLine("正确示例 A：用户消息 m-12 为‘以后回答直接一点，先说结论’，返回：")
        appendLine("{\"memories\":[{\"content\":\"用户偏好回答先给结论并保持直接简洁\",\"category\":\"preference\",\"tags\":[\"沟通方式\"],\"evidence_message_ids\":[\"m-12\"]}]}")
        appendLine("正确示例 B：只有‘你好’和一次天气查询，返回：")
        appendLine("{\"memories\":[]}")
        appendLine("错误示例：引用助手消息、使用不存在的 ID、输出 ```json、把‘今天在上海’推断为长期居住城市、把 API Key 写入 memory，或在 JSON 前后增加说明。")
        appendLine()
        appendLine("会话标题：${source.title}")
        appendLine("<existing_memories>")
        existingMemories.filter { it.enabled }.take(MAX_EXISTING_MEMORIES).forEach { memory ->
            appendLine("${memory.id}\t${memory.category.orEmpty()}\t${memory.content}")
        }
        appendLine("</existing_memories>")
        appendLine("<evidence_index>")
        source.messages.forEach { message ->
            appendLine("[${message.id}] ${if (message.role == "user") "用户" else "助手"}: ${message.content}")
        }
        appendLine("</evidence_index>")
    }

    fun parseStrict(raw: String): Result<List<ConversationMemoryDraft>> = runCatching {
        require(raw.isNotBlank()) { "记忆提炼正文为空" }
        require(!raw.contains("```")) { "记忆提炼必须是纯 JSON" }
        val root = Json.parseToJsonElement(raw.trim()) as? JsonObject
            ?: error("记忆提炼根节点必须是 JSON 对象")
        require(root.keys == setOf("memories")) { "记忆提炼根节点字段非法" }
        val memories = root["memories"] as? JsonArray ?: error("memories 必须是数组")
        require(memories.size <= MAX_RESULT_MEMORIES) { "memories 数量超过上限" }
        memories.map { element ->
            val item = element as? JsonObject ?: error("memory 项必须是对象")
            require(item.keys.all { it in ALLOWED_ITEM_KEYS }) { "memory 项包含未知字段" }
            require(REQUIRED_ITEM_KEYS.all(item::containsKey)) { "memory 项缺少必填字段" }
            val content = item.requiredString("content").trim()
            val category = item.requiredString("category")
            val tags = item.requiredStringArray("tags")
            val evidence = item.requiredStringArray("evidence_message_ids")
            require(content.isNotBlank()) { "memory content 不能为空" }
            require(category in ALLOWED_CATEGORIES) { "memory category 非法" }
            require(evidence.isNotEmpty() && evidence.none(String::isBlank)) { "memory 必须包含证据 ID" }
            ConversationMemoryDraft(
                content = content,
                category = category,
                tags = tags.map(String::trim).filter(String::isNotBlank).distinct(),
                evidenceMessageIds = evidence.distinct(),
                existingMemoryId = item.optionalString("existing_memory_id")?.trim()?.takeIf(String::isNotBlank),
            )
        }
    }

    internal fun parse(raw: String): List<ConversationMemoryDraft> = parseStrict(
        raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
    ).getOrDefault(emptyList())

    private fun JsonObject.requiredString(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull ?: error("$key 必须是字符串")

    private fun JsonObject.optionalString(key: String): String? {
        val value = get(key) ?: return null
        return (value as? JsonPrimitive)?.contentOrNull ?: error("$key 必须是字符串")
    }

    private fun JsonObject.requiredStringArray(key: String): List<String> {
        val array = get(key) as? JsonArray ?: error("$key 必须是数组")
        return array.map { element ->
            (element as? JsonPrimitive)?.contentOrNull ?: error("$key 只能包含字符串")
        }
    }

    private companion object {
        const val MAX_EXISTING_MEMORIES = 80
        const val MAX_RESULT_MEMORIES = 12
        val ALLOWED_CATEGORIES = setOf("preference", "profile", "ongoing", "decision", "recurring_topic", "explicit")
        val REQUIRED_ITEM_KEYS = setOf("content", "category", "tags", "evidence_message_ids")
        val ALLOWED_ITEM_KEYS = REQUIRED_ITEM_KEYS + "existing_memory_id"
    }
}
