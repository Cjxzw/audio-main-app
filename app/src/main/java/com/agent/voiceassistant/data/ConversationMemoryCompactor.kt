package com.agent.voiceassistant.data

import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.LlmClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConversationMemoryCompactor {
    suspend fun compact(
        client: LlmClient,
        source: ConversationCompressionSource,
        existingMemories: List<StoredMemory>,
    ): List<ConversationMemoryDraft> {
        val completion = client.streamChat(
            CloudSpeechClient.ChatRequest(
                messages = listOf(
                    CloudSpeechClient.LlmMessage("system", SYSTEM_PROMPT),
                    CloudSpeechClient.LlmMessage(
                        "user",
                        buildInput(source, existingMemories.filter { it.enabled }),
                    ),
                ),
                tools = emptyList(),
                thinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
                maxCompletionTokens = 1_500,
            ),
        ) {}
        return parse(completion.message.content.orEmpty())
    }

    internal fun buildInput(
        source: ConversationCompressionSource,
        existingMemories: List<StoredMemory>,
    ): String = buildString {
        appendLine("<existing_memories>")
        existingMemories.take(MAX_EXISTING_MEMORIES).forEach { memory ->
            appendLine("${memory.id}\t${memory.category.orEmpty()}\t${memory.content}")
        }
        appendLine("</existing_memories>")
        appendLine("<conversation title=\"${source.title}\">")
        append(trimTranscript(source.messages))
        appendLine()
        append("</conversation>")
    }

    internal fun parse(raw: String): List<ConversationMemoryDraft> {
        val jsonText = raw.substringAfter('{', "").let { body ->
            if (body.isBlank()) return emptyList()
            "{" + body.substringBeforeLast('}', "") + "}"
        }
        val root = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return emptyList()
        val memories = root["memories"] as? JsonArray ?: return emptyList()
        return memories.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val content = item.string("content").trim()
            val evidence = item.stringArray("evidence_message_ids")
            if (content.isBlank() || evidence.isEmpty()) return@mapNotNull null
            ConversationMemoryDraft(
                content = content,
                category = item.string("category").takeIf { it in ALLOWED_CATEGORIES },
                tags = item.stringArray("tags"),
                evidenceMessageIds = evidence,
                existingMemoryId = item.string("existing_memory_id").takeIf(String::isNotBlank),
            )
        }.take(MAX_RESULT_MEMORIES)
    }

    private fun trimTranscript(messages: List<ConversationCompressionMessage>): String {
        val lines = messages.map { message ->
            "[${message.id}] ${if (message.role == "user") "用户" else "助手"}: ${message.content}"
        }
        val full = lines.joinToString("\n")
        if (full.length <= MAX_TRANSCRIPT_CHARS) return full
        val head = full.take(MAX_TRANSCRIPT_CHARS / 3)
        val tail = full.takeLast(MAX_TRANSCRIPT_CHARS * 2 / 3)
        return "$head\n...[中间闲聊已截断]...\n$tail"
    }

    private fun JsonObject.string(key: String): String =
        runCatching { get(key)?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")

    private fun JsonObject.stringArray(key: String): List<String> =
        runCatching { get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() }.getOrDefault(emptyList())

    private companion object {
        const val MAX_TRANSCRIPT_CHARS = 30_000
        const val MAX_EXISTING_MEMORIES = 80
        const val MAX_RESULT_MEMORIES = 12
        val ALLOWED_CATEGORIES = setOf("preference", "profile", "ongoing", "decision", "recurring_topic", "explicit")
        val SYSTEM_PROMPT = """
            你负责把日常聊天提炼成跨会话长期记忆。只输出 JSON，不要解释：
            {"memories":[{"content":"简洁、独立、可长期使用的事实","category":"preference|profile|ongoing|decision|recurring_topic|explicit","tags":["标签"],"evidence_message_ids":["消息ID"],"existing_memory_id":"可选的已有记忆ID"}]}

            保留：用户明确要求记住的信息；稳定身份、偏好、习惯、禁忌和交流方式；反复讨论的话题；长期目标、进行中的项目、已确认决定与约束；用户对助手的纠正。
            忽略：天气查询和预报、当前位置查询和临时地点、寒暄、一次性闲聊、短期情绪、已过期状态、工具结果、助手未经用户确认的推测、API Key 和精确定位等敏感值。
            稳定居住城市与“现在在哪里”不同：只有用户明确陈述长期居住信息或要求记住时才保留。
            每条记忆必须由 evidence_message_ids 指向原对话中的真实消息；没有证据就不要输出。
            若新内容与 existing_memories 中某条相同或可合并，填写 existing_memory_id，不要创建近义重复项。
            不要把助手说过但用户未确认的内容当作用户事实。没有值得长期保存的信息时返回 {"memories":[]}。
        """.trimIndent()
    }
}
