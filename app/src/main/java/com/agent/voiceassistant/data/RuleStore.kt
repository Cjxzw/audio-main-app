package com.agent.voiceassistant.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** User-owned global rules. They are intentionally separate from model-managed memories. */
class RuleStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val file = File(context.filesDir, "agent-rules.json")
    private val shared = SHARED_STORES.computeIfAbsent(file.canonicalPath) {
        SharedRuleState(loadState())
    }
    private val lock: Any = shared
    private var state: RuleStoreState
        get() = shared.state
        set(value) {
            shared.state = value
        }

    init {
        synchronized(lock) {
            if (!file.exists()) persistLocked()
        }
    }

    fun rules(): List<UserRule> = synchronized(lock) {
        state.rules.sortedBy(UserRule::createdAt)
    }

    fun currentRevision(): Long = synchronized(lock) { state.revision }

    fun snapshot(): RuleSnapshot = synchronized(lock) {
        RuleSnapshot(state.revision, state.rules.filter(UserRule::enabled).sortedBy(UserRule::createdAt))
    }

    /** Returns null when a caller's patch chain has fallen outside retained history. */
    fun changesSince(revision: Long): List<RuleChange>? = synchronized(lock) {
        if (revision >= state.revision) return@synchronized emptyList()
        val changes = state.changes.filter { it.revision > revision }
        if (changes.isEmpty() || changes.first().revision != revision + 1L) return@synchronized null
        changes
    }

    fun createRule(title: String, body: String): UserRule = synchronized(lock) {
        require(state.rules.size < MAX_RULES) { "规则数量已达上限" }
        val normalizedBody = normalizeBody(body)
        require(state.rules.sumOf { it.body.length } + normalizedBody.length <= MAX_TOTAL_BODY_CHARS) {
            "规则正文总长度已达上限"
        }
        val rule = UserRule(
            id = UUID.randomUUID().toString(),
            title = normalizeTitle(title),
            body = normalizedBody,
            version = 1,
        )
        state.rules.add(rule)
        appendChangeLocked(RuleOperation.ADD, rule)
        rule
    }

    fun updateRule(id: String, title: String, body: String): UserRule? = synchronized(lock) {
        val index = state.rules.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val previous = state.rules[index]
        val normalizedBody = normalizeBody(body)
        require(state.rules.sumOf { it.body.length } - previous.body.length + normalizedBody.length <= MAX_TOTAL_BODY_CHARS) {
            "规则正文总长度已达上限"
        }
        val updated = previous.copy(
            title = normalizeTitle(title),
            body = normalizedBody,
            version = previous.version + 1,
            updatedAt = System.currentTimeMillis(),
        )
        state.rules[index] = updated
        appendChangeLocked(RuleOperation.UPDATE, updated)
        updated
    }

    fun deleteRule(id: String): Boolean = synchronized(lock) {
        val index = state.rules.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val removed = state.rules.removeAt(index)
        appendChangeLocked(RuleOperation.DELETE, removed)
        true
    }

    fun setEnabled(id: String, enabled: Boolean): UserRule? = synchronized(lock) {
        val index = state.rules.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val current = state.rules[index]
        if (current.enabled == enabled) return@synchronized current
        val updated = current.copy(
            enabled = enabled,
            version = current.version + 1,
            updatedAt = System.currentTimeMillis(),
        )
        state.rules[index] = updated
        appendChangeLocked(if (enabled) RuleOperation.ENABLE else RuleOperation.DISABLE, updated)
        updated
    }

    private fun appendChangeLocked(operation: RuleOperation, rule: UserRule) {
        val revision = state.revision + 1L
        state.revision = revision
        state.changes.add(RuleChange(revision, operation, rule, System.currentTimeMillis()))
        while (state.changes.size > MAX_CHANGE_HISTORY) state.changes.removeAt(0)
        persistLocked()
    }

    private fun normalizeTitle(title: String): String = title.trim()
        .also {
            require(it.isNotBlank()) { "规则名称不能为空" }
            require(it.length <= MAX_TITLE_CHARS) { "规则名称不能超过 $MAX_TITLE_CHARS 个字符" }
        }

    private fun normalizeBody(body: String): String = body.trim()
        .also {
            require(it.isNotBlank()) { "规则正文不能为空" }
            require(it.length <= MAX_BODY_CHARS) { "单条规则正文不能超过 $MAX_BODY_CHARS 个字符" }
        }

    private fun loadState(): RuleStoreState {
        if (file.exists()) {
            return runCatching { json.decodeFromString<RuleStoreState>(file.readText()) }
                .getOrElse { initialState() }
        }
        return initialState()
    }

    private fun initialState(): RuleStoreState {
        val diagnosticRule = UserRule(
            id = BUILTIN_DIAGNOSTIC_RULE_ID,
            title = "问题诊断",
            body = BUILTIN_DIAGNOSTIC_RULE,
            version = 1,
        )
        return RuleStoreState(
            revision = 1L,
            rules = mutableListOf(diagnosticRule),
            changes = mutableListOf(RuleChange(1L, RuleOperation.ADD, diagnosticRule)),
        )
    }

    private fun persistLocked() {
        file.writeText(json.encodeToString(state))
    }

    private companion object {
        private const val MAX_RULES = 64
        private const val MAX_TITLE_CHARS = 80
        private const val MAX_BODY_CHARS = 8_000
        private const val MAX_TOTAL_BODY_CHARS = 16_000
        private const val MAX_CHANGE_HISTORY = 128
        private const val BUILTIN_DIAGNOSTIC_RULE_ID = "builtin-diagnostic-rule"
        private val BUILTIN_DIAGNOSTIC_RULE = """
            当用户讨论本 App 的异常、Bug、报错、卡顿、日志、源码或行为不符合预期时，自行判断是否需要诊断。
            需要多步取证、读取日志或源码、或结论存在不确定性时，先调用 request_deep_reasoning。
            优先读取 /logs 的相关时间范围；只有日志不足以解释问题时，再使用代码图谱和 /source。
            诊断结论必须区分已确认事实、可能原因和未验证项。没有明确日志或源码证据时，不得把推测称为根因；时间接近不等于因果。
            诊断完成后，可用 write 将完整报告保存到 /workspace/diagnostics；若用户要求保留问题记录，再用 memory_create 保存简短索引，并标明已确认或假设。
        """.trimIndent()
        private val SHARED_STORES = ConcurrentHashMap<String, SharedRuleState>()
    }
}

@Serializable
data class RuleStoreState(
    var revision: Long = 0L,
    val rules: MutableList<UserRule> = mutableListOf(),
    val changes: MutableList<RuleChange> = mutableListOf(),
)

@Serializable
data class UserRule(
    val id: String,
    val title: String,
    val body: String,
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val enabled: Boolean = true,
)

@Serializable
data class RuleChange(
    val revision: Long,
    val operation: RuleOperation,
    val rule: UserRule,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
enum class RuleOperation { ADD, UPDATE, DELETE, ENABLE, DISABLE }

data class RuleSnapshot(val revision: Long, val rules: List<UserRule>)

private data class SharedRuleState(var state: RuleStoreState)
