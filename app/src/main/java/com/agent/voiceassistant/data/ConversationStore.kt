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
import java.util.concurrent.ConcurrentHashMap

class ConversationStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val file = File(context.filesDir, "main-agent-store.json")
    private val toolTraceDir = File(context.filesDir, "agent-runtime/tool-traces").apply { mkdirs() }

    private val shared = SHARED_STORES.computeIfAbsent(file.canonicalPath) {
        SharedConversationState(loadState())
    }
    private val lock: Any = shared
    private var state: StoreState
        get() = shared.state
        set(value) {
            shared.state = value
        }

    val currentConversationId: String
        get() = synchronized(lock) { state.currentConversationId }

    fun conversationSummaries(): List<ConversationSummary> = synchronized(lock) {
        state.sessions.sortedByDescending { it.updatedAt }.map { session ->
            ConversationSummary(
                id = session.id,
                title = session.title.ifBlank { defaultConversationTitle(session.createdAt) },
                preview = session.messages.asReversed()
                    .firstOrNull { it.chatVisible != false && it.content.isNotBlank() }
                    ?.content.orEmpty().replace('\n', ' ').take(80),
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                messageCount = session.messages.count { it.chatVisible != false },
                current = session.id == state.currentConversationId,
                memoryCompressedAt = session.memoryCompressedAt,
            )
        }
    }

    fun currentConversationSummary(): ConversationSummary = synchronized(lock) {
        val session = currentSessionLocked()
        ConversationSummary(
            id = session.id,
            title = session.title.ifBlank { defaultConversationTitle(session.createdAt) },
            preview = "",
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            messageCount = session.messages.count { it.chatVisible != false },
            current = true,
            memoryCompressedAt = session.memoryCompressedAt,
        )
    }

    fun recentChatMessages(limit: Int = 500): List<ChatMessage> = synchronized(lock) {
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
                    attachmentPaths = message.attachments.map(StoredAttachment::virtualPath),
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
        attachments: List<StoredAttachment> = emptyList(),
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
            attachments = attachments,
        )
        synchronized(lock) {
            val session = currentSessionLocked()
            session.messages.add(message)
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
        val now = System.currentTimeMillis()
        val session = ConversationSession(
            id = UUID.randomUUID().toString(),
            title = defaultConversationTitle(now),
            createdAt = now,
            updatedAt = now,
        )
        synchronized(lock) {
            state.currentConversationId = session.id
            state.sessions.add(session)
            persistLocked()
        }
        Timber.i("ConversationStore: new session ${session.id}, reason=$reason")
        return session
    }

    fun switchConversation(id: String): Boolean = synchronized(lock) {
        if (state.sessions.none { it.id == id }) return@synchronized false
        state.currentConversationId = id
        persistLocked()
        true
    }

    fun renameConversation(id: String, title: String): Boolean = synchronized(lock) {
        val normalized = title.trim().take(MAX_CONVERSATION_TITLE_CHARS)
        require(normalized.isNotBlank()) { "会话标题不能为空" }
        val session = state.sessions.firstOrNull { it.id == id } ?: return@synchronized false
        session.title = normalized
        persistLocked()
        true
    }

    fun deleteConversation(id: String): Boolean = synchronized(lock) {
        if (!state.sessions.removeAll { it.id == id }) return@synchronized false
        if (state.sessions.isEmpty()) {
            val now = System.currentTimeMillis()
            val replacement = ConversationSession(
                id = UUID.randomUUID().toString(),
                title = defaultConversationTitle(now),
                createdAt = now,
                updatedAt = now,
            )
            state.sessions.add(replacement)
            state.currentConversationId = replacement.id
        } else if (state.currentConversationId == id) {
            state.currentConversationId = state.sessions.maxBy { it.updatedAt }.id
        }
        persistLocked()
        true
    }

    fun conversationForCompression(id: String = currentConversationId): ConversationCompressionSource? =
        synchronized(lock) {
            val session = state.sessions.firstOrNull { it.id == id } ?: return@synchronized null
            if (session.memoryCompressedAt != null && session.memoryCompressedAt!! >= session.updatedAt) {
                return@synchronized null
            }
            val messages = session.messages
                .filter { it.role == "user" || it.role == "assistant" }
                .filter { it.content.isNotBlank() }
                .map { ConversationCompressionMessage(it.id, it.role, it.content, it.timestamp) }
            if (messages.none { it.role == "user" }) return@synchronized null
            ConversationCompressionSource(
                id = session.id,
                title = session.title.ifBlank { defaultConversationTitle(session.createdAt) },
                updatedAt = session.updatedAt,
                messages = messages,
            )
        }

    fun mergeConversationMemories(
        conversationId: String,
        drafts: List<ConversationMemoryDraft>,
        compressedAt: Long = System.currentTimeMillis(),
    ) = synchronized(lock) {
        val session = state.sessions.firstOrNull { it.id == conversationId } ?: return@synchronized
        val validMessageIds = session.messages.mapTo(hashSetOf(), StoredMessage::id)
        val userMessageIds = session.messages.filter { it.role == "user" }.mapTo(hashSetOf(), StoredMessage::id)
        drafts.take(MAX_MEMORIES_PER_COMPRESSION).forEach { draft ->
            val content = draft.content.trim().take(MAX_MEMORY_CONTENT_CHARS)
            val evidence = draft.evidenceMessageIds.filter { it in validMessageIds }.distinct()
            if (content.isBlank() || evidence.none { it in userMessageIds } || SENSITIVE_VALUE.containsMatchIn(content)) {
                return@forEach
            }
            val existing = draft.existingMemoryId
                ?.let { id -> state.memories.firstOrNull { it.id == id && it.enabled } }
                ?: state.memories.firstOrNull { it.content.equals(content, ignoreCase = true) && it.enabled }
            if (existing != null) {
                val index = state.memories.indexOf(existing)
                state.memories[index] = existing.copy(
                    content = if (existing.autoGenerated) content else existing.content,
                    tags = (existing.tags + draft.tags + AUTO_MEMORY_TAG + listOfNotNull(draft.category))
                        .map(String::trim).filter(String::isNotBlank).distinct(),
                    category = existing.category ?: draft.category,
                    occurrenceCount = existing.occurrenceCount + 1,
                    sourceConversationIds = (existing.sourceConversationIds + conversationId).distinct(),
                    updatedAt = compressedAt,
                )
            } else {
                state.memories.add(
                    StoredMemory(
                        id = UUID.randomUUID().toString(),
                        content = content,
                        tags = (draft.tags + AUTO_MEMORY_TAG + listOfNotNull(draft.category))
                            .map(String::trim).filter(String::isNotBlank).distinct(),
                        sourceMessageId = evidence.first(),
                        sourceConversationIds = listOf(conversationId),
                        category = draft.category,
                        occurrenceCount = 1,
                        autoGenerated = true,
                        createdAt = compressedAt,
                        updatedAt = compressedAt,
                    ),
                )
            }
        }
        session.memoryCompressedAt = compressedAt
        trimMemoriesLocked()
        persistLocked()
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
        val enabled = state.memories.filter { it.enabled }
        if (q.isBlank()) {
            enabled.takeLast(limit).asReversed()
        } else {
            enabled
                .asReversed()
                .filter { memory ->
                    memory.content.contains(q, ignoreCase = true) ||
                        memory.tags.any { it.contains(q, ignoreCase = true) }
                }
                .take(limit)
        }
    }

    fun memories(): List<StoredMemory> = synchronized(lock) {
        state.memories.sortedByDescending { it.updatedAt }
    }

    fun updateMemory(id: String, content: String, tags: List<String>): StoredMemory? = synchronized(lock) {
        val index = state.memories.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = state.memories[index].copy(
            content = content.trim(),
            tags = tags.map(String::trim).filter(String::isNotBlank).distinct(),
            updatedAt = System.currentTimeMillis(),
        )
        state.memories[index] = updated
        persistLocked()
        updated
    }

    fun setMemoryEnabled(id: String, enabled: Boolean): StoredMemory? = synchronized(lock) {
        val index = state.memories.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = state.memories[index].copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        state.memories[index] = updated
        persistLocked()
        updated
    }

    fun deleteMemory(id: String): Boolean = synchronized(lock) {
        val removed = state.memories.removeAll { it.id == id }
        if (removed) persistLocked()
        removed
    }

    fun setLocation(location: StoredLocation) {
        synchronized(lock) {
            state.lastLocation = location
            persistLocked()
        }
    }

    fun lastLocation(): StoredLocation? = synchronized(lock) { state.lastLocation }

    fun contextSummary(): String = synchronized(lock) {
        memorySummaryLocked()
    }

    fun sessionContextSnapshot(skillSummary: String): String = synchronized(lock) {
        val session = currentSessionLocked()
        session.contextSnapshot?.let { return@synchronized it }
        val snapshot = buildString {
            appendLine("Skill 索引：")
            appendLine(skillSummary)
        }.trim()
        session.contextSnapshot = snapshot
        persistLocked()
        snapshot
    }

    private fun memorySummaryLocked(): String =
        buildString {
            val memories = state.memories.filter { it.enabled }
                .sortedWith(
                    compareByDescending<StoredMemory> { !it.autoGenerated }
                        .thenByDescending { it.occurrenceCount }
                        .thenByDescending { it.updatedAt },
                )
                .take(MAX_CONTEXT_MEMORIES)
            if (memories.isNotEmpty()) {
                appendLine("用户记忆：")
                var usedChars = 0
                memories.forEach { memory ->
                    val line = "- ${memory.content}"
                    if (usedChars + line.length <= MAX_CONTEXT_MEMORY_CHARS) {
                        appendLine(line)
                        usedChars += line.length
                    }
                }
            } else {
                appendLine("用户记忆：暂无")
            }
        }.trim()

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
            initial.sessions.add(newConversation(initial.currentConversationId))
            return initial
        }
        return runCatching {
            json.decodeFromString<StoreState>(file.readText())
        }.onFailure {
            Timber.w(it, "ConversationStore: failed to load, reset store")
        }.getOrElse {
            StoreState().also { reset -> reset.sessions.add(newConversation(reset.currentConversationId)) }
        }.also { loaded ->
            if (loaded.sessions.none { it.id == loaded.currentConversationId }) {
                loaded.sessions.add(newConversation(loaded.currentConversationId))
            }
            loaded.sessions.forEach { session ->
                if (session.title.isBlank()) session.title = defaultConversationTitle(session.createdAt)
                session.contextSnapshot = null
            }
        }
    }

    private fun currentSessionLocked(): ConversationSession {
        val current = state.sessions.firstOrNull { it.id == state.currentConversationId }
        if (current != null) return current
        val session = newConversation(state.currentConversationId)
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
        private const val MAX_CONTEXT_MEMORIES = 24
        private const val MAX_CONTEXT_MEMORY_CHARS = 8_000
        private const val MAX_CONVERSATION_TITLE_CHARS = 60
        private const val MAX_MEMORIES_PER_COMPRESSION = 12
        private const val MAX_MEMORY_CONTENT_CHARS = 500
        private const val MAX_TOOL_TRACE_FILES = 500
        private const val AUTO_MEMORY_TAG = "会话提炼"
        private val SENSITIVE_VALUE = Regex("(?i)\\b(?:sk|tp)[A-Za-z0-9_-]{16,}")
        private val SHARED_STORES = ConcurrentHashMap<String, SharedConversationState>()
    }

    private fun compactLegacyToolDisplay(content: String): String {
        val withoutStatus = content.removeSuffix(" ✅").removeSuffix(" ❌").trimEnd()
        val rawJsonStart = withoutStatus.indexOf(" {")
        return if (rawJsonStart > 0) withoutStatus.substring(0, rawJsonStart) else withoutStatus
    }
}

private fun defaultConversationTitle(timestamp: Long): String =
    SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(timestamp))

private fun newConversation(id: String): ConversationSession {
    val now = System.currentTimeMillis()
    return ConversationSession(
        id = id,
        title = defaultConversationTitle(now),
        createdAt = now,
        updatedAt = now,
    )
}

private data class SharedConversationState(var state: StoreState)

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
    var contextSnapshot: String? = null,
    var memoryCompressedAt: Long? = null,
)

data class ConversationSummary(
    val id: String,
    val title: String,
    val preview: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val current: Boolean,
    val memoryCompressedAt: Long?,
)

data class ConversationCompressionSource(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ConversationCompressionMessage>,
)

data class ConversationCompressionMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
)

data class ConversationMemoryDraft(
    val content: String,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val evidenceMessageIds: List<String> = emptyList(),
    val existingMemoryId: String? = null,
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
    val attachments: List<StoredAttachment> = emptyList(),
)

@Serializable
data class StoredAttachment(
    val virtualPath: String,
    val mimeType: String? = null,
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
    val sourceConversationIds: List<String> = emptyList(),
    val category: String? = null,
    val occurrenceCount: Int = 1,
    val autoGenerated: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val enabled: Boolean = true,
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
