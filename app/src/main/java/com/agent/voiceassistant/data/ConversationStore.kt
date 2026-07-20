package com.agent.voiceassistant.data

import android.content.Context
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.ui.ChatMessage
import com.agent.voiceassistant.ui.ChatPresentation
import com.agent.voiceassistant.ui.ChatRole
import com.agent.voiceassistant.ui.ToolDisplayStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ConversationStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val file = File(context.filesDir, "main-agent-store.json")
    private val toolTraceDir = File(context.filesDir, "agent-runtime/tool-traces").apply { mkdirs() }

    private val lock = Any()
    private var state = loadState()

    val currentConversationId: String
        get() = synchronized(lock) { state.currentConversationId }

    fun recentChatMessages(limit: Int = 100): List<ChatMessage> = synchronized(lock) {
        currentSessionLocked().messages
            .filter { it.chatVisible != false }
            .takeLast(limit)
            .map { it.toChatMessage() }
    }

    fun llmHistory(excludeMessageId: String? = null): List<CloudSpeechClient.LlmMessage> = synchronized(lock) {
        currentSessionLocked().messages
            .filter { message ->
                message.llmVisible ?: (message.role == "user" || message.role == "assistant")
            }
            .filter { it.role == "user" || it.role == "assistant" || it.role == "tool" }
            .filterNot { it.id == excludeMessageId }
            .map { message ->
                CloudSpeechClient.LlmMessage(
                    role = message.role,
                    content = message.content,
                    toolCalls = message.toolCalls.map { call ->
                        CloudSpeechClient.ToolCall(call.id, call.name, call.arguments)
                    },
                    toolCallId = message.toolCallId,
                )
            }
    }

    fun addMessage(
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        toolCallId: String? = null,
        toolStatus: ToolDisplayStatus? = null,
        presentation: ChatPresentation = ChatPresentation.STANDARD,
    ): StoredMessage {
        val normalizedRole = when (role) {
            "assistant", "bot" -> "assistant"
            "system", "tool" -> role
            else -> "user"
        }
        val message = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = normalizedRole,
            content = content,
            timestamp = timestamp,
            toolCallId = toolCallId,
            toolStatus = toolStatus?.name,
            presentation = presentation.name,
        )
        synchronized(lock) {
            val session = currentSessionLocked()
            session.messages.add(message)
            if (normalizedRole == "user" && session.title.isBlank()) {
                session.title = content.take(24)
            }
            session.updatedAt = System.currentTimeMillis()
            persistLocked()
        }
        return message
    }

    fun updateMessage(
        messageId: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        toolStatus: ToolDisplayStatus? = null,
    ): StoredMessage? {
        synchronized(lock) {
            val session = currentSessionLocked()
            val index = session.messages.indexOfFirst { it.id == messageId }
            if (index < 0) return null
            val updated = session.messages[index].copy(
                content = content,
                timestamp = timestamp,
                toolStatus = toolStatus?.name ?: session.messages[index].toolStatus,
            )
            session.messages[index] = updated
            session.updatedAt = timestamp
            persistLocked()
            return updated
        }
    }

    fun addLlmMessage(
        message: CloudSpeechClient.LlmMessage,
        timestamp: Long = System.currentTimeMillis(),
        chatVisible: Boolean = false,
    ): StoredMessage {
        val normalizedRole = when (message.role) {
            "assistant", "tool", "user" -> message.role
            else -> "system"
        }
        val stored = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = normalizedRole,
            content = message.content.orEmpty(),
            timestamp = timestamp,
            toolCalls = message.toolCalls.map { call ->
                StoredToolCall(call.id, call.name, call.arguments)
            },
            toolCallId = message.toolCallId,
            llmVisible = true,
            chatVisible = chatVisible,
        )
        synchronized(lock) {
            val session = currentSessionLocked()
            session.messages.add(stored)
            session.updatedAt = System.currentTimeMillis()
            persistLocked()
        }
        return stored
    }

    fun addToolResult(
        turnId: String,
        call: CloudSpeechClient.ToolCall,
        result: CloudSpeechClient.LlmMessage,
        success: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    ): StoredMessage {
        val rawContent = result.content.orEmpty()
        persistToolTrace(
            StoredToolTrace(
                turnId = turnId,
                toolCallId = call.id,
                toolName = call.name,
                arguments = call.arguments,
                result = rawContent,
                success = success,
                timestamp = timestamp,
            ),
        )
        val compactContent = ToolHistoryPolicy.compact(rawContent, turnId, call.id)
        Timber.i(
            "agent.tool.persisted_chars turn=$turnId id=${call.id} " +
                "raw=${rawContent.length} stored=${compactContent.length}",
        )
        return addLlmMessage(
            message = result.copy(content = compactContent),
            timestamp = timestamp,
        )
    }

    fun startNewConversation(reason: String = "用户开启新话题"): ConversationSession {
        val session = ConversationSession(
            id = UUID.randomUUID().toString(),
            title = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            state.currentConversationId = session.id
            state.sessions.add(session)
            persistLocked()
        }
        Timber.i("ConversationStore: new session ${session.id}, reason=$reason")
        return session
    }

    fun addMemory(content: String, tags: List<String> = emptyList(), sourceMessageId: String? = null): StoredMemory {
        val memory = StoredMemory(
            id = UUID.randomUUID().toString(),
            content = content.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            sourceMessageId = sourceMessageId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            state.memories.add(memory)
            trimMemoriesLocked()
            persistLocked()
        }
        return memory
    }

    fun searchMemories(query: String, limit: Int = 5): List<StoredMemory> = synchronized(lock) {
        val q = query.trim()
        if (q.isBlank()) {
            state.memories.takeLast(limit).asReversed()
        } else {
            state.memories
                .asReversed()
                .filter { memory ->
                    memory.content.contains(q, ignoreCase = true) ||
                        memory.tags.any { it.contains(q, ignoreCase = true) }
                }
                .take(limit)
        }
    }

    fun setLocation(location: StoredLocation) {
        synchronized(lock) {
            state.lastLocation = location
            persistLocked()
        }
    }

    fun lastLocation(): StoredLocation? = synchronized(lock) { state.lastLocation }

    fun contextSummary(): String = synchronized(lock) {
        buildString {
            val memories = state.memories.takeLast(5)
            if (memories.isNotEmpty()) {
                appendLine("用户记忆：")
                memories.forEach { appendLine("- ${it.content}") }
            } else {
                appendLine("用户记忆：暂无")
            }
        }.trim()
    }

    fun recentUserTimingSummary(limit: Int = 4, now: Long = System.currentTimeMillis()): String =
        synchronized(lock) {
            val users = currentSessionLocked().messages
                .filter { it.role == "user" }
                .takeLast(limit)
            if (users.isEmpty()) return@synchronized "暂无上一轮用户输入"
            users.joinToString(separator = "\n") { message ->
                val elapsedSeconds = ((now - message.timestamp).coerceAtLeast(0L) / 1000L)
                "- ${elapsedSeconds} 秒前收到一轮用户输入"
            }
        }

    private fun loadState(): StoreState {
        if (!file.exists()) {
            val initial = StoreState()
            initial.sessions.add(ConversationSession(id = initial.currentConversationId))
            return initial
        }
        return runCatching {
            json.decodeFromString<StoreState>(file.readText())
        }.onFailure {
            Timber.w(it, "ConversationStore: failed to load, reset store")
        }.getOrElse {
            StoreState().also { reset -> reset.sessions.add(ConversationSession(id = reset.currentConversationId)) }
        }.also { loaded ->
            if (loaded.sessions.none { it.id == loaded.currentConversationId }) {
                loaded.sessions.add(ConversationSession(id = loaded.currentConversationId))
            }
        }
    }

    private fun currentSessionLocked(): ConversationSession {
        val current = state.sessions.firstOrNull { it.id == state.currentConversationId }
        if (current != null) return current
        val session = ConversationSession(id = state.currentConversationId)
        state.sessions.add(session)
        return session
    }

    private fun trimMemoriesLocked() {
        while (state.memories.size > MAX_MEMORIES) {
            state.memories.removeAt(0)
        }
    }

    private fun persistLocked() {
        runCatching {
            file.writeText(json.encodeToString(state))
        }.onFailure {
            Timber.e(it, "ConversationStore: persist failed")
        }
    }

    private fun persistToolTrace(trace: StoredToolTrace) {
        synchronized(lock) {
            runCatching {
                val turnDir = File(toolTraceDir, trace.turnId.safeFilePart()).apply { mkdirs() }
                File(turnDir, "${trace.toolCallId.safeFilePart()}.json")
                    .writeText(json.encodeToString(trace))
                toolTraceDir.walkTopDown()
                    .filter { it.isFile && it.extension == "json" }
                    .sortedByDescending { it.lastModified() }
                    .drop(MAX_TOOL_TRACE_FILES)
                    .forEach { it.delete() }
            }.onFailure {
                Timber.w(it, "ConversationStore: tool trace persist failed")
            }
        }
    }

    private fun String.safeFilePart(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "unknown" }

    private fun StoredMessage.toChatMessage(): ChatMessage {
        val role = when (role) {
            "assistant" -> ChatRole.BOT
            "system", "tool" -> ChatRole.SYSTEM
            else -> ChatRole.USER
        }
        val storedStatus = toolStatus?.let { stored ->
            runCatching { ToolDisplayStatus.valueOf(stored) }.getOrNull()
        }
        val status = storedStatus ?: when {
            content.trimEnd().endsWith("✅") -> ToolDisplayStatus.SUCCEEDED
            content.trimEnd().endsWith("❌") -> ToolDisplayStatus.FAILED
            toolCallId != null -> ToolDisplayStatus.RUNNING
            else -> null
        }
        val displayContent = if (toolCallId != null) {
            compactLegacyToolDisplay(content)
        } else {
            content
        }
        return ChatMessage(
            role = role,
            text = displayContent,
            timestamp = timestamp,
            toolCallId = toolCallId,
            toolStatus = status,
            presentation = presentation?.let {
                runCatching { ChatPresentation.valueOf(it) }.getOrNull()
            } ?: ChatPresentation.STANDARD,
        )
    }

    private companion object {
        private const val MAX_MEMORIES = 500
        private const val MAX_TOOL_TRACE_FILES = 500
    }

    private fun compactLegacyToolDisplay(content: String): String {
        val withoutStatus = content.removeSuffix(" ✅").removeSuffix(" ❌").trimEnd()
        val rawJsonStart = withoutStatus.indexOf(" {")
        return if (rawJsonStart > 0) withoutStatus.substring(0, rawJsonStart) else withoutStatus
    }
}

internal object ToolHistoryPolicy {
    const val MAX_PERSISTED_RESULT_CHARS = 3_000

    fun compact(content: String, turnId: String, toolCallId: String): String {
        if (content.length <= MAX_PERSISTED_RESULT_CHARS) return content
        val marker = "\n...[长期历史已压缩，完整记录 turn=$turnId call=$toolCallId]...\n"
        val available = (MAX_PERSISTED_RESULT_CHARS - marker.length).coerceAtLeast(0)
        val headSize = (available * 2 / 3).coerceAtLeast(0)
        val tailSize = (available - headSize).coerceAtLeast(0)
        return content.take(headSize) + marker + content.takeLast(tailSize)
    }
}

@Serializable
data class StoreState(
    var currentConversationId: String = UUID.randomUUID().toString(),
    val sessions: MutableList<ConversationSession> = mutableListOf(),
    val memories: MutableList<StoredMemory> = mutableListOf(),
    var lastLocation: StoredLocation? = null,
)

@Serializable
data class ConversationSession(
    val id: String,
    var title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val messages: MutableList<StoredMessage> = mutableListOf(),
)

@Serializable
data class StoredMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val toolCalls: List<StoredToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolStatus: String? = null,
    val llmVisible: Boolean? = null,
    val chatVisible: Boolean? = null,
    val presentation: String? = null,
)

@Serializable
data class StoredToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
data class StoredToolTrace(
    val turnId: String,
    val toolCallId: String,
    val toolName: String,
    val arguments: String,
    val result: String,
    val success: Boolean,
    val timestamp: Long,
)

@Serializable
data class StoredMemory(
    val id: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val sourceMessageId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class StoredLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val provider: String? = null,
    val address: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceTimestamp: Long = timestamp,
    val altitudeMeters: Double? = null,
    val verticalAccuracyMeters: Float? = null,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val bearingDegrees: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val elapsedRealtimeNanos: Long? = null,
    val elapsedRealtimeUncertaintyNanos: Double? = null,
    val isMock: Boolean? = null,
) {
    fun isFresh(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - timestamp <= maxAgeMs

    fun userPlaceText(): String =
        address?.takeIf { it.isNotBlank() }?.let { "$it 附近" }
            ?: "已定位，但暂时无法解析成街道地址"

    fun displayText(): String {
        val coord = "%.5f, %.5f".format(Locale.US, latitude, longitude)
        val accuracy = accuracyMeters?.let { "，精度约 ${it.toInt()} 米" }.orEmpty()
        val addressText = address?.takeIf { it.isNotBlank() }?.let { "$it，" }.orEmpty()
        return "$addressText$coord$accuracy"
    }
}
