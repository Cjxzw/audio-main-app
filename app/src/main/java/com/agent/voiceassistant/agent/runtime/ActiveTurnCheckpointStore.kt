package com.agent.voiceassistant.agent.runtime

import android.content.Context
import android.util.AtomicFile
import com.agent.voiceassistant.cloud.CloudSpeechClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStreamWriter

class ActiveTurnCheckpointStore(context: Context) {
    @Serializable
    data class Message(
        val role: String,
        val content: String? = null,
        val reasoningContent: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolCallId: String? = null,
        val attachmentPaths: List<String> = emptyList(),
    )

    @Serializable
    data class ToolCall(val id: String, val name: String, val arguments: String)

    @Serializable
    data class Snapshot(
        val version: Int = 1,
        val turnId: String,
        val conversationId: String,
        val source: String,
        val speakReplies: Boolean,
        val userRequest: String,
        val phase: String,
        val messages: List<Message>,
        val thinkingEnabled: Boolean,
        val businessToolCallCount: Int,
        val activeElapsedMs: Long,
        val activeBudgetStarted: Boolean,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private val file = AtomicFile(File(context.filesDir, "active-agent-turn.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Snapshot? = synchronized(LOCK) {
        runCatching {
            file.openRead().bufferedReader().use { json.decodeFromString<Snapshot>(it.readText()) }
        }.getOrNull()
    }

    fun save(snapshot: Snapshot) = synchronized(LOCK) {
        val output = file.startWrite()
        try {
            OutputStreamWriter(output).apply {
                write(json.encodeToString(snapshot))
                flush()
            }
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    fun clear() = synchronized(LOCK) { file.delete() }

    companion object {
        private val LOCK = Any()

        fun encode(messages: List<CloudSpeechClient.LlmMessage>): List<Message> = messages.map { message ->
            Message(
                role = message.role,
                content = message.content,
                reasoningContent = message.reasoningContent,
                toolCalls = message.toolCalls.map { ToolCall(it.id, it.name, it.arguments) },
                toolCallId = message.toolCallId,
                attachmentPaths = message.attachmentPaths,
            )
        }

        fun decode(messages: List<Message>): List<CloudSpeechClient.LlmMessage> = messages.map { message ->
            CloudSpeechClient.LlmMessage(
                role = message.role,
                content = message.content,
                reasoningContent = message.reasoningContent,
                toolCalls = message.toolCalls.map { CloudSpeechClient.ToolCall(it.id, it.name, it.arguments) },
                toolCallId = message.toolCallId,
                attachmentPaths = message.attachmentPaths,
            )
        }
    }
}
