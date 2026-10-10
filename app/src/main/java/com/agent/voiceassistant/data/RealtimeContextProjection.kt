package com.agent.voiceassistant.data

import com.agent.voiceassistant.agent.ExperimentalReplyParser
import com.agent.voiceassistant.ui.ChatStreamState
import java.text.SimpleDateFormat
import java.util.Locale

/** Context-only projections: never include reasoning or hidden system instructions. */
object RealtimeContextProjection {
    /** Main-session handover contains the conversation surface only. Internal
     * reasoning, tool calls and tool results remain in the detached REALTIME
     * conversation for inspection and are never injected into Main context. */
    fun transcript(messages: List<StoredMessage>): String = messages
        .filter { it.role == "user" || it.role == "assistant" }
        .flatMap { lines(it, includeResults = false, includeToolCalls = false) }
        .fold(mutableListOf<String>()) { result, line ->
            val repeatedUserEcho = line.startsWith("用户：") && result.any { it == line }
            if (!repeatedUserEcho && result.lastOrNull() != line) result += line
            result
        }
        .joinToString("\n")

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

    /** Startup projection where each completed Realtime call is one independent turn. */
    fun snapshotWithRealtimeNotes(
        messages: List<StoredMessage>,
        realtimeNotes: List<String>,
        maxTurns: Int = 10,
        maxChars: Int = 1200,
    ): String {
        val turns = mutableListOf<Pair<Long, String>>()
        val mainTurns = mutableListOf<MutableList<StoredMessage>>()
        messages.forEach { message ->
            if (message.role == "user") mainTurns.add(mutableListOf())
            mainTurns.lastOrNull()?.add(message)
        }
        mainTurns.forEach { turn ->
            val body = turn.flatMap { lines(it, includeResults = false) }.joinToString("\n")
            if (body.isNotBlank()) turns.add((turn.firstOrNull()?.timestamp ?: 0L) to body)
        }
        realtimeNotes.forEach { note ->
            val stamp = REALTIME_NOTE_PATTERN.find(note)?.groupValues?.getOrNull(1)
                ?.let { runCatching { NOTE_DATE.parse(it)?.time }.getOrNull() } ?: Long.MAX_VALUE
            turns.add(stamp to note)
        }
        val selected = mutableListOf<String>()
        var chars = 0
        turns.sortedBy { it.first }.asReversed().take(maxTurns).forEach { (_, body) ->
            val cost = body.length + if (selected.isEmpty()) 0 else 1
            if (chars + cost <= maxChars) {
                selected += body
                chars += cost
            }
        }
        return selected.asReversed().joinToString("\n")
    }

    fun assistantBody(raw: String): String {
        val parsed = ExperimentalReplyParser.parse(raw)
        return listOf(parsed.answer, parsed.details).filter(String::isNotBlank).joinToString("\n\n")
    }

    private fun lines(
        message: StoredMessage,
        includeResults: Boolean,
        includeToolCalls: Boolean = true,
    ): List<String> = buildList {
        if (message.role == "user" && message.chatVisible != false && message.content.isNotBlank()) {
            add("用户：${message.content}")
        }
        if (message.role == "assistant") {
            val body = assistantBody(message.content)
            if ((message.chatVisible != false || message.toolCalls.isNotEmpty()) && body.isNotBlank()) add("助手：$body")
            if (includeToolCalls && message.toolCalls.isNotEmpty()) add("[工具调用] ${message.toolCalls.joinToString { it.name }}")
        }
        if (includeResults && message.role == "tool" && message.toolCallId != null) {
            add("[工具结果/${message.toolStatus ?: "unknown"}] ${message.content}")
        }
    }

    private val NOTE_DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val REALTIME_NOTE_PATTERN = Regex("Realtime 通话记录（([^）]+)）")
}
