package com.agent.voiceassistant.data

import com.agent.voiceassistant.agent.ExperimentalReplyParser
import com.agent.voiceassistant.ui.ChatStreamState

/** Context-only projections: never include reasoning or hidden system instructions. */
object RealtimeContextProjection {
    fun transcript(messages: List<StoredMessage>): String = messages.flatMap { lines(it, includeResults = true) }.joinToString("\n")

    fun snapshot(messages: List<StoredMessage>, maxTurns: Int = 10, maxChars: Int = 1200): String {
        val turns = mutableListOf<MutableList<StoredMessage>>()
        messages.forEach { message ->
            if (message.role == "user") turns += mutableListOf<StoredMessage>()
            turns.lastOrNull()?.add(message)
        }
        val selected = mutableListOf<String>()
        var chars = 0
        for (turn in turns.asReversed().take(maxTurns)) {
            // Never include a partial streaming turn in the startup snapshot.
            if (turn.any { it.streamState == ChatStreamState.STREAMING.name }) break
            val body = turn.flatMap { lines(it, includeResults = false) }.joinToString("\n")
            val cost = body.length + if (selected.isEmpty()) 0 else 1
            if (chars + cost > maxChars) break
            selected += body
            chars += cost
        }
        return selected.asReversed().joinToString("\n")
    }

    fun assistantBody(raw: String): String {
        val parsed = ExperimentalReplyParser.parse(raw)
        return listOf(parsed.answer, parsed.details).filter(String::isNotBlank).joinToString("\n\n")
    }

    private fun lines(message: StoredMessage, includeResults: Boolean): List<String> = buildList {
        if (message.role == "user" && message.chatVisible != false && message.content.isNotBlank()) {
            add("用户：${message.content}")
        }
        if (message.role == "assistant") {
            val body = assistantBody(message.content)
            if ((message.chatVisible != false || message.toolCalls.isNotEmpty()) && body.isNotBlank()) add("助手：$body")
            if (message.toolCalls.isNotEmpty()) add("[工具调用] ${message.toolCalls.joinToString { it.name }}")
        }
        if (includeResults && message.role == "tool" && message.toolCallId != null) {
            add("[工具结果/${message.toolStatus ?: "unknown"}] ${message.content}")
        }
    }
}
