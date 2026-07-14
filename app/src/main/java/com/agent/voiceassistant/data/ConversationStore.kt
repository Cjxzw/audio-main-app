package com.agent.voiceassistant.data

import android.content.Context
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.ui.ChatMessage
import com.agent.voiceassistant.ui.ChatRole
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

    private val lock = Any()
    private var state = loadState()

    val currentConversationId: String
        get() = synchronized(lock) { state.currentConversationId }

    fun recentChatMessages(limit: Int = 100): List<ChatMessage> = synchronized(lock) {
        currentSessionLocked().messages
            .takeLast(limit)
            .map { it.toChatMessage() }
    }

    fun llmHistory(limit: Int = 12): List<CloudSpeechClient.LlmMessage> = synchronized(lock) {
        currentSessionLocked().messages
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(limit)
            .map { CloudSpeechClient.LlmMessage(role = it.role, content = it.content) }
    }

    fun addMessage(role: String, content: String, timestamp: Long = System.currentTimeMillis()): StoredMessage {
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
        )
        synchronized(lock) {
            val session = currentSessionLocked()
            session.messages.add(message)
            if (normalizedRole == "user" && session.title.isBlank()) {
                session.title = content.take(24)
            }
            trimMessages(session)
            persistLocked()
        }
        return message
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
            trimSessionsLocked()
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
            val location = state.lastLocation
            if (location != null) {
                appendLine("当前定位：${location.displayText()}")
            } else {
                appendLine("当前定位：未知")
            }
            val memories = state.memories.takeLast(5)
            if (memories.isNotEmpty()) {
                appendLine("用户记忆：")
                memories.forEach { appendLine("- ${it.content}") }
            } else {
                appendLine("用户记忆：暂无")
            }
        }.trim()
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

    private fun trimMessages(session: ConversationSession) {
        session.updatedAt = System.currentTimeMillis()
        while (session.messages.size > MAX_MESSAGES_PER_SESSION) {
            session.messages.removeAt(0)
        }
    }

    private fun trimSessionsLocked() {
        while (state.sessions.size > MAX_SESSIONS) {
            state.sessions.removeAt(0)
        }
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

    private fun StoredMessage.toChatMessage(): ChatMessage {
        val role = when (role) {
            "assistant" -> ChatRole.BOT
            "system", "tool" -> ChatRole.SYSTEM
            else -> ChatRole.USER
        }
        return ChatMessage(role = role, text = content, timestamp = timestamp)
    }

    private companion object {
        private const val MAX_MESSAGES_PER_SESSION = 300
        private const val MAX_SESSIONS = 20
        private const val MAX_MEMORIES = 500
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
