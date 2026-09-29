package com.agent.voiceassistant.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Extracts only durable, actionable information from a Realtime transcript. */
class RealtimeSessionCompactor {
    fun instruction(transcript: String): String = buildString {
        appendLine("这是 Realtime 语音会话结束后的后台信息提炼任务，不是用户的新请求。")
        appendLine("不得调用工具，不得向用户答复。请先在内部判断，再只输出一个合法 JSON 对象。")
        appendLine("JSON 必须严格匹配：{\"digest\":\"可供主会话后续使用的精简信息\"}")
        appendLine()
        appendLine("提炼规则：")
        appendLine("1. 只保留下一次主会话可能需要的事实、决定、用户明确要求、任务、约束、偏好、待办和未解决问题。")
        appendLine("2. 删除寒暄、重复确认、口头禅、闲聊、情绪性铺垫、已经完成的无关问答和语音识别噪声。")
        appendLine("3. 不要把助手的推测写成事实；不确定内容必须明确标注为待确认。")
        appendLine("4. 不要保留 API Key、令牌、密码、精确定位或其他敏感值。")
        appendLine("5. 使用简短的中文条目，避免复述整段对话；没有可复用信息时返回 {\"digest\":\"\"}。")
        appendLine("6. 不要输出 Markdown 代码围栏、JSON 之外的说明或思考过程。")
        appendLine()
        appendLine("<realtime_transcript>")
        appendLine(transcript.take(MAX_TRANSCRIPT_CHARS))
        appendLine("</realtime_transcript>")
    }

    fun parseStrict(raw: String): Result<String> = runCatching {
        require(raw.isNotBlank()) { "Realtime 提炼正文为空" }
        require(!raw.contains("```")) { "Realtime 提炼必须是纯 JSON" }
        val root = Json.parseToJsonElement(raw.trim()) as? JsonObject
            ?: error("Realtime 提炼根节点必须是 JSON 对象")
        require(root.keys == setOf("digest")) { "Realtime 提炼根节点字段非法" }
        val digest = (root["digest"] as? JsonPrimitive)?.contentOrNull
            ?: error("digest 必须是字符串")
        digest.trim().take(MAX_DIGEST_CHARS)
    }

    private companion object {
        const val MAX_TRANSCRIPT_CHARS = 60_000
        const val MAX_DIGEST_CHARS = 4_000
    }
}
