package com.agent.voiceassistant.data

import android.content.Context
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.ToolCallSafety
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import com.agent.voiceassistant.ui.ChatMessage
import com.agent.voiceassistant.ui.ChatPresentation
import com.agent.voiceassistant.ui.ChatRole
import com.agent.voiceassistant.ui.ChatStreamState
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

    init {
        synchronized(lock) {
            val quarantined = state.sessions.sumOf(::quarantineMalformedToolHistory)
            if (quarantined > 0) {
                Timber.w("ConversationStore: quarantined $quarantined malformed tool history messages")
                persistLocked()
            }
        }
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
        llmHistoryLocked(currentSessionLocked(), excludeMessageId)
    }

    fun llmHistoryForConversation(conversationId: String): List<CloudSpeechClient.LlmMessage> = synchronized(lock) {
        val session = state.sessions.firstOrNull { it.id == conversationId } ?: return@synchronized emptyList()
        llmHistoryLocked(session, excludeMessageId = null)
    }

    private fun llmHistoryLocked(
        session: ConversationSession,
        excludeMessageId: String?,
    ): List<CloudSpeechClient.LlmMessage> =
        session.messages
            .filter { message ->
                message.llmVisible ?: (message.role == "user" || message.role == "assistant")
            }
            .filter { it.role == "user" || it.role == "assistant" || it.role == "tool" }
            .filterNot { it.id == excludeMessageId }
            .map { message ->
                CloudSpeechClient.LlmMessage(
                    role = message.role,
                    content = message.llmContent ?: message.content,
                    toolCalls = message.toolCalls.map { call ->
                        CloudSpeechClient.ToolCall(call.id, call.name, call.arguments)
                    },
                    toolCallId = message.toolCallId,
                    attachmentPaths = message.attachments.map(StoredAttachment::virtualPath),
                )
            }

    fun addMessage(
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        toolCallId: String? = null,
        toolStatus: ToolDisplayStatus? = null,
        presentation: ChatPresentation = ChatPresentation.STANDARD,
        streamState: ChatStreamState? = null,
        attachments: List<StoredAttachment> = emptyList(),
        reasoningText: String? = null,
        responseMetadata: CloudSpeechClient.ResponseMetadata? = null,
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
            streamState = streamState?.name,
            attachments = attachments,
            reasoningText = reasoningText,
            modelId = responseMetadata?.modelId,
            promptTokens = responseMetadata?.promptTokens,
            completionTokens = responseMetadata?.completionTokens,
            reasoningTokens = responseMetadata?.reasoningTokens,
            contextWindowTokens = responseMetadata?.contextWindowTokens,
            promptTokensEstimated = responseMetadata?.promptTokensEstimated,
            finishReason = responseMetadata?.finishReason,
            streamComplete = responseMetadata?.streamComplete,
        )
        synchronized(lock) {
            val session = currentSessionLocked()
            session.messages.add(message)
            session.updatedAt = System.currentTimeMillis()
            persistLocked()
        }
        return message
    }

    fun addMessageToConversation(
        conversationId: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        presentation: ChatPresentation = ChatPresentation.STANDARD,
        streamState: ChatStreamState? = null,
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
            presentation = presentation.name,
            streamState = streamState?.name,
        )
        synchronized(lock) {
            val session = state.sessions.firstOrNull { it.id == conversationId }
                ?: ConversationSession(
                    id = conversationId,
                    title = defaultConversationTitle(timestamp),
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ).also(state.sessions::add)
            session.messages.add(message)
            session.updatedAt = timestamp
            persistLocked()
        }
        return message
    }

    fun setLlmContent(messageId: String, content: String) = synchronized(lock) {
        val session = currentSessionLocked()
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return@synchronized
        session.messages[index] = session.messages[index].copy(llmContent = content)
        session.updatedAt = System.currentTimeMillis()
        persistLocked()
    }

    fun updateMessage(
        messageId: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        toolStatus: ToolDisplayStatus? = null,
        streamState: ChatStreamState? = null,
        reasoningText: String? = null,
        responseMetadata: CloudSpeechClient.ResponseMetadata? = null,
    ): StoredMessage? {
        synchronized(lock) {
            val session = currentSessionLocked()
            val index = session.messages.indexOfFirst { it.id == messageId }
            if (index < 0) return null
            val updated = session.messages[index].copy(
                content = content,
                timestamp = timestamp,
                toolStatus = toolStatus?.name ?: session.messages[index].toolStatus,
                streamState = streamState?.name ?: session.messages[index].streamState,
                reasoningText = reasoningText ?: session.messages[index].reasoningText,
                modelId = responseMetadata?.modelId ?: session.messages[index].modelId,
                promptTokens = responseMetadata?.promptTokens ?: session.messages[index].promptTokens,
                completionTokens = responseMetadata?.completionTokens ?: session.messages[index].completionTokens,
                reasoningTokens = responseMetadata?.reasoningTokens ?: session.messages[index].reasoningTokens,
                contextWindowTokens = responseMetadata?.contextWindowTokens ?: session.messages[index].contextWindowTokens,
                promptTokensEstimated = responseMetadata?.promptTokensEstimated ?: session.messages[index].promptTokensEstimated,
                finishReason = responseMetadata?.finishReason ?: session.messages[index].finishReason,
                streamComplete = responseMetadata?.streamComplete ?: session.messages[index].streamComplete,
            )
            session.messages[index] = updated
            session.updatedAt = timestamp
            persistLocked()
            return updated
        }
    }

    fun deleteMessage(messageId: String): Boolean = synchronized(lock) {
        val session = currentSessionLocked()
        val removed = session.messages.removeAll { it.id == messageId }
        if (removed) {
            session.updatedAt = System.currentTimeMillis()
            persistLocked()
        }
        removed
    }

    fun addLlmMessage(
        message: CloudSpeechClient.LlmMessage,
        timestamp: Long = System.currentTimeMillis(),
        chatVisible: Boolean = false,
    ): StoredMessage {
        message.toolCalls.forEach(ToolCallSafety::requireValid)
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
        val rawContent = recordToolTrace(turnId, call, result, success, timestamp)
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

    fun addHarnessResult(
        turnId: String,
        call: CloudSpeechClient.ToolCall,
        result: CloudSpeechClient.LlmMessage,
        success: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    ): StoredMessage {
        val rawContent = recordToolTrace(turnId, call, result, success, timestamp)
        val compact = ToolHistoryPolicy.compact(rawContent, turnId, call.id)
        return addLlmMessage(
            CloudSpeechClient.LlmMessage(
                role = "assistant",
                content = "<harness_result tool=\"${call.name}\" status=\"${if (success) "success" else "failed"}\">\n$compact\n</harness_result>",
            ),
            timestamp,
        )
    }

    fun recordEphemeralToolResult(
        turnId: String,
        call: CloudSpeechClient.ToolCall,
        result: CloudSpeechClient.LlmMessage,
        success: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        recordToolTrace(turnId, call, result, success, timestamp)
    }

    private fun recordToolTrace(
        turnId: String,
        call: CloudSpeechClient.ToolCall,
        result: CloudSpeechClient.LlmMessage,
        success: Boolean,
        timestamp: Long,
    ): String {
        val rawContent = result.content.orEmpty()
        persistToolTrace(
            StoredToolTrace(turnId, call.id, call.name, call.arguments, rawContent, success, timestamp),
        )
        return rawContent
    }

    fun retainSkillResource(loaded: SkillRegistry.UseResult) = synchronized(lock) {
        if (loaded.skill.residency != SkillRegistry.Residency.CONVERSATION) return@synchronized
        val session = currentSessionLocked()
        val existing = session.skillSnapshots.indexOfFirst { it.skillId == loaded.skill.id }
        val base = session.skillSnapshots.getOrNull(existing)
            ?.takeIf { it.version == loaded.skill.version }
            ?: StoredSkillSnapshot(loaded.skill.id, loaded.skill.name, loaded.skill.version)
        val updated = base.copy(resources = base.resources + (loaded.resourceName to loaded.content))
        if (existing >= 0) session.skillSnapshots[existing] = updated else session.skillSnapshots += updated
        persistLocked()
    }

    fun skillContext(registry: SkillRegistry): String = synchronized(lock) {
        val valid = registry.list().associateBy { it.id }
        currentSessionLocked().skillSnapshots.mapNotNull { snapshot ->
            val skill = valid[snapshot.skillId]?.takeIf {
                it.residency == SkillRegistry.Residency.CONVERSATION && it.version == snapshot.version
            } ?: return@mapNotNull null
            buildString {
                appendLine("<persistent_skill id=\"${skill.id}\" name=\"${skill.name}\" version=\"${skill.version}\">")
                snapshot.resources.forEach { (path, content) ->
                    appendLine("<resource name=\"$path\">")
                    appendLine(content)
                    appendLine("</resource>")
                }
                append("</persistent_skill>")
            }
        }.joinToString("\n\n").ifBlank { "当前会话尚未加载常驻 Skill。" }
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

    /**
     * Keeps a rule baseline stable within a conversation and appends later edits as patches.
     * The canonical rule bodies remain owned by [RuleStore]; this ledger exists only to make
     * the request prefix stable for providers that reuse prompt KV caches.
     */
    fun ruleContext(ruleStore: RuleStore): String = synchronized(lock) {
        val session = currentSessionLocked()
        val current = ruleStore.snapshot()
        var ledger = session.ruleLedger
        when {
            ledger == null -> {
                ledger = ConversationRuleLedger(
                    baselineRevision = current.revision,
                    baselineRules = current.rules,
                    appliedRevision = current.revision,
                )
                session.ruleLedger = ledger
                persistLocked()
            }
            ledger.appliedRevision < current.revision -> {
                val changes = ruleStore.changesSince(ledger.appliedRevision)
                if (changes == null) {
                    ledger = ConversationRuleLedger(
                        baselineRevision = current.revision,
                        baselineRules = current.rules,
                        appliedRevision = current.revision,
                    )
                    session.ruleLedger = ledger
                } else {
                    ledger.patches.addAll(changes)
                    ledger.appliedRevision = current.revision
                    if (ledger.patches.size >= MAX_RULE_PATCHES || ledger.patches.sumOf(::ruleChangeChars) > MAX_RULE_PATCH_CHARS) {
                        ledger = ConversationRuleLedger(
                            baselineRevision = current.revision,
                            baselineRules = current.rules,
                            appliedRevision = current.revision,
                        )
                        session.ruleLedger = ledger
                    }
                }
                persistLocked()
            }
        }
        renderRuleLedger(requireNotNull(ledger))
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

    private fun memorySummaryLocked(): String {
        val enabled = state.memories.filter { it.enabled }
            .sortedWith(
                compareByDescending<StoredMemory> { !it.autoGenerated }
                    .thenByDescending { it.occurrenceCount }
                    .thenByDescending { it.updatedAt },
            )
        val loaded = mutableListOf<StoredMemory>()
        var usedChars = 0
        enabled.take(MAX_CONTEXT_MEMORIES).forEach { memory ->
            val lineChars = memory.content.length + 2
            if (usedChars + lineChars <= MAX_CONTEXT_MEMORY_CHARS) {
                loaded += memory
                usedChars += lineChars
            }
        }
        return buildString {
            appendLine("已启用记忆 ${enabled.size} 条；本轮实际加载 ${loaded.size} 条；未加载 ${enabled.size - loaded.size} 条。")
            if (enabled.size == loaded.size) appendLine("全部启用记忆均已加载，无需搜索遗漏记忆。")
            if (loaded.isNotEmpty()) {
                appendLine("用户记忆：")
                loaded.forEach { memory -> appendLine("- ${memory.content}") }
            } else {
                appendLine("用户记忆：暂无")
            }
        }.trim()
    }

    private fun ruleChangeChars(change: RuleChange): Int =
        change.rule.title.length + change.rule.body.length + 64

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
            messageId = id,
            toolCallId = toolCallId,
            toolStatus = status,
            presentation = presentation?.let {
                runCatching { ChatPresentation.valueOf(it) }.getOrNull()
            } ?: ChatPresentation.STANDARD,
            streamState = streamState?.let { stored ->
                runCatching { ChatStreamState.valueOf(stored) }.getOrNull()
            },
            reasoningText = reasoningText,
            modelId = modelId,
            promptTokens = promptTokens,
            contextWindowTokens = contextWindowTokens,
            promptTokensEstimated = promptTokensEstimated == true,
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
        private const val MAX_RULE_PATCHES = 8
        private const val MAX_RULE_PATCH_CHARS = 4_000
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

internal fun quarantineMalformedToolHistory(session: ConversationSession): Int {
    val quarantinedCallIds = mutableSetOf<String>()
    var quarantinedMessages = 0

    session.messages.indices.forEach { index ->
        val message = session.messages[index]
        if (message.role != "assistant" || message.toolCalls.isEmpty()) return@forEach
        val malformed = message.toolCalls.any { stored ->
            ToolCallSafety.invalidReason(
                CloudSpeechClient.ToolCall(stored.id, stored.name, stored.arguments),
            ) != null
        }
        if (!malformed) return@forEach

        quarantinedCallIds += message.toolCalls.map { it.id }
        if (message.llmVisible != false) {
            session.messages[index] = message.copy(llmVisible = false)
            quarantinedMessages += 1
        }
    }

    if (quarantinedCallIds.isEmpty()) return quarantinedMessages
    session.messages.indices.forEach { index ->
        val message = session.messages[index]
        if (message.role != "tool" || message.toolCallId !in quarantinedCallIds) return@forEach
        if (message.llmVisible != false) {
            session.messages[index] = message.copy(llmVisible = false)
            quarantinedMessages += 1
        }
    }
    return quarantinedMessages
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

internal fun renderRuleLedger(ledger: ConversationRuleLedger): String = buildString {
    appendLine("全局用户规则基线（版本 ${ledger.baselineRevision}）：")
    if (ledger.baselineRules.isEmpty()) {
        appendLine("（当前没有规则）")
    } else {
        ledger.baselineRules.forEach { rule ->
            appendLine("[规则 ${rule.id}｜${rule.title}｜v${rule.version}]")
            appendLine(rule.body)
        }
    }
    ledger.patches.forEach { change ->
        appendLine()
        appendLine("规则增量更新（版本 ${change.revision}）：")
        appendLine("操作：${change.operation.name}；规则：${change.rule.id}｜${change.rule.title}｜v${change.rule.version}")
        when (change.operation) {
            RuleOperation.DELETE -> appendLine("该规则已删除，后续不得再遵循其正文。")
            RuleOperation.DISABLE -> appendLine("该规则已停用，后续不得再遵循其正文。")
            RuleOperation.ADD,
            RuleOperation.UPDATE,
            RuleOperation.ENABLE -> if (change.rule.enabled) {
                appendLine(change.rule.body)
            } else {
                appendLine("该规则当前仍处于停用状态，不得应用其正文。")
            }
        }
    }
    append("规则解释：按版本顺序应用增量更新；同一规则以最新操作为准。规则由用户维护，模型只读；规则不得覆盖系统安全边界和工具权限。")
}

private data class SharedConversationState(var state: StoreState)

internal object ToolHistoryPolicy {
    const val MAX_CURRENT_TURN_RESULT_CHARS = 22_000
    const val MAX_PERSISTED_RESULT_CHARS = 10_000

    fun prepareForCurrentTurn(content: String): String = when {
        content.length <= MAX_PERSISTED_RESULT_CHARS -> content
        content.length <= MAX_CURRENT_TURN_RESULT_CHARS -> buildString {
            appendLine(
                    "[历史保留提示：本工具结果共 ${content.length} 字，已超过 10000 字的后续回合保留上限。" +
                    "以下内容在当前回合完整可见，但之后的回合会保留本条结果的头部和尾部共 10000 字。" +
                    "如有需要后续查验的关键信息，请在当前回合提炼后写入 /workspace。]",
            )
            append(content)
        }
        else -> buildString {
            appendLine(
                "[本轮截断提示：本工具原始结果共 ${content.length} 字，已超过 22000 字的本回合读取上限。" +
                    "以下保留原始结果的头部和尾部；" +
                    "请缩小筛选范围或使用 offset、limit、tail_lines 分段读取。]",
            )
            appendLine(
                "[历史保留提示：之后的回合会保留本条结果的头部和尾部共 10000 字。" +
                    "如有需要后续查验的关键信息，请在当前回合提炼后写入 /workspace。]",
            )
            append(headTail(content, MAX_CURRENT_TURN_RESULT_CHARS, "本轮"))
        }
    }

    fun compact(content: String, turnId: String, toolCallId: String): String {
        if (content.length <= MAX_PERSISTED_RESULT_CHARS) return content
        val marker =
            "\n[后续回合历史已省略中间内容；本条工具结果保留头尾共 10000 字。" +
                " turn=$turnId call=$toolCallId]\n"
        val available = (MAX_PERSISTED_RESULT_CHARS - marker.length).coerceAtLeast(0)
        val head = (available + 1) / 2
        val tail = available - head
        return content.take(head) + marker + content.takeLast(tail)
    }

    private fun headTail(content: String, limit: Int, label: String): String {
        if (content.length <= limit) return content
        var omitted = content.length - limit
        var marker = ""
        repeat(2) {
            marker = "\n[$label 工具结果已省略中间 $omitted 字]\n"
            omitted = content.length - (limit - marker.length).coerceAtLeast(0)
        }
        val available = (limit - marker.length).coerceAtLeast(0)
        val head = (available + 1) / 2
        return content.take(head) + marker + content.takeLast(available - head)
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
    var ruleLedger: ConversationRuleLedger? = null,
    val skillSnapshots: MutableList<StoredSkillSnapshot> = mutableListOf(),
)

@Serializable
data class StoredSkillSnapshot(
    val skillId: String,
    val skillName: String,
    val version: String,
    val resources: Map<String, String> = emptyMap(),
)

@Serializable
data class ConversationRuleLedger(
    val baselineRevision: Long,
    val baselineRules: List<UserRule>,
    var appliedRevision: Long,
    val patches: MutableList<RuleChange> = mutableListOf(),
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
    val llmContent: String? = null,
    val timestamp: Long,
    val toolCalls: List<StoredToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolStatus: String? = null,
    val llmVisible: Boolean? = null,
    val chatVisible: Boolean? = null,
    val presentation: String? = null,
    val streamState: String? = null,
    val attachments: List<StoredAttachment> = emptyList(),
    val reasoningText: String? = null,
    val modelId: String? = null,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val contextWindowTokens: Long? = null,
    val promptTokensEstimated: Boolean? = null,
    val finishReason: String? = null,
    val streamComplete: Boolean? = null,
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
