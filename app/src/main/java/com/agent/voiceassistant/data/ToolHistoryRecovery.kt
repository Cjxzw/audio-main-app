package com.agent.voiceassistant.data

import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.ToolMessageIntegrity
import kotlinx.serialization.json.Json
import java.io.File

/** A trace is accepted only when both its ID and persisted result match; ambiguous traces are ignored. */
internal class ToolHistoryRecovery(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val traces by lazy {
        directory.walkTopDown().filter { it.isFile && it.extension == "json" && it.length() <= 1024 * 1024 }
            .mapNotNull { file -> runCatching { json.decodeFromString<StoredToolTrace>(file.readText()) }.getOrNull() }
            .groupBy { it.toolCallId }
    }

    fun repair(messages: List<CloudSpeechClient.LlmMessage>): List<CloudSpeechClient.LlmMessage> =
        ToolMessageIntegrity.repair(messages) { message ->
            traces[message.toolCallId].orEmpty().filter { trace ->
                ToolHistoryPolicy.compact(trace.result, trace.turnId, trace.toolCallId) == message.content
            }.singleOrNull()?.let { trace ->
                CloudSpeechClient.ToolCall(trace.toolCallId, trace.toolName, trace.arguments)
            }
        }
}
