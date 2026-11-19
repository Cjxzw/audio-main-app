package com.agent.voiceassistant.agent.runtime

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** One durable slot shared by every conversation and every Skill mutation tool. */
class SkillApprovalStore(private val root: File, private val workspace: File, private val registry: SkillRegistry) {
    @Serializable
    data class Request(
        val id: String,
        val conversationId: String,
        val operation: String,
        val skillId: String,
        val name: String,
        val runtime: String = "text",
        val entry: String? = null,
        val permissions: Set<String> = emptySet(),
        val permissionsDeclared: Boolean = false,
        val manifestVersion: Int = 1,
        val reviewPolicyVersion: Int = 1,
        val expectedVersion: String? = null,
        val candidate: String,
        val digest: String,
        val summary: String,
        val deferred: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
        val phase: String = "pending",
    )
    @Serializable
    data class Outcome(val requestId: String, val conversationId: String, val status: String, val message: String)
    @Serializable
    private data class State(val pending: Request? = null, val outcomes: List<Outcome> = emptyList())

    private val file = File(root, "skill-approval.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var state: State
    val pending: MutableStateFlow<Request?>

    init {
        root.mkdirs()
        state = if (file.exists()) json.decodeFromString<State>(file.readText()) else State()
        pending = MutableStateFlow(state.pending)
        // A process death between package publication and slot completion is reconciled by content hash.
        state.pending?.takeIf { it.phase == "publishing" }?.let { request ->
            val target = File(root, "recovery-${request.id}")
            try {
                val installed = runCatching { registry.exportTo(request.skillId, target) }.isSuccess
                if (installed && hashTree(target) == request.digest) finish(request, "approved", "Skill「${request.name}」已批准并生效")
                else save(state.copy(pending = request.copy(phase = "pending")))
            } finally { target.deleteRecursively() }
        }
    }

    @Synchronized
    fun propose(operation: String, payload: JsonObject, conversationId: String): Request {
        check(state.pending == null) { "已有待处理的 Skill 注册或变更，请先批准或拒绝当前待办" }
        val id = UUID.randomUUID().toString()
        val staging = File(root, "skill-proposals/$id")
        val sandbox = SkillRegistry(File(staging, "skills"))
        fun text(key: String) = (payload[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val existing = if (operation == "skill.edit") registry.findByName(text("skill_name"))
            ?: error("没有找到 Skill：${text("skill_name")}") else null
        require(existing?.system != true) { "系统 Skill 不能编辑" }
        val candidate = File(workspace, ".skill-proposals/$id")
        try {
            val prepared = when (operation) {
                "skill.register" -> {
                    val source = File(workspace, text("source_path").removePrefix("/workspace/")).canonicalFile
                    require(source.toPath().startsWith(workspace.canonicalFile.toPath()) && source != workspace.canonicalFile) { "来源必须位于 /workspace" }
                    val copyWorkspace = File(staging, "workspace").apply { mkdirs() }
                    val stagedName = if (source.isDirectory) "candidate" else source.name
                    source.copyRecursively(File(copyWorkspace, stagedName))
                    sandbox.registerFromWorkspace(copyWorkspace, "/workspace/$stagedName", text("name"), text("description"),
                        text("core_file").ifBlank { "SKILL.md" }, text("compatibility_notes"),
                        (payload["reviewed_files"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }).skill
                }
                "skill.create" -> sandbox.create(text("skill_name"), text("description"), text("content"))
                "skill.edit" -> {
                    registry.exportTo(existing!!.id, File(staging, "skills/${existing.id}"))
                    if (text("operation") == "replace_from_workspace") {
                        val source = File(workspace, text("source_path").removePrefix("/workspace/")).canonicalFile
                        require(source.toPath().startsWith(workspace.canonicalFile.toPath()) && source != workspace.canonicalFile) { "来源必须位于 /workspace" }
                        require(source.isDirectory) { "完整替换需要候选目录" }
                        require(text("expected_sha256") == existing.version) { "Skill 已变化，请重新读取" }
                        val temp = File(staging, "replacement-workspace").apply { mkdirs() }
                        source.copyRecursively(File(temp, "candidate"))
                        val replacementRegistry = SkillRegistry(File(staging, "replacement-skills"))
                        val result = replacementRegistry.registerFromWorkspace(temp, "/workspace/candidate", text("name").ifBlank { existing.name },
                            text("description").ifBlank { existing.description }, "SKILL.md", "完整候选替换",
                            source.walkTopDown().filter(File::isFile).map { it.relativeTo(source).invariantSeparatorsPath }.toList()).skill
                        replacementRegistry.exportTo(result.id, candidate)
                        result
                    } else sandbox.edit(existing.name, text("operation"), text("resource_name").ifBlank { "SKILL.md" },
                        text("expected_sha256"), payload["old_text"]?.jsonPrimitive?.contentOrNull,
                        payload["new_text"]?.jsonPrimitive?.contentOrNull)
                }
                else -> error("未知 Skill 事务")
            }
            if (!candidate.exists()) sandbox.exportTo(prepared.id, candidate)
            val currentSkills = registry.listAll()
            require(currentSkills.none { it.id != existing?.id && it.name.trim().equals(prepared.name.trim(), true) }) { "Skill 名称已存在，请使用 skill_edit" }
            val targetId = existing?.id ?: if (currentSkills.any { it.id == prepared.id }) "${prepared.id.take(40)}-${id.take(8)}" else prepared.id
            val files = candidate.walkTopDown().filter(File::isFile).map { it.relativeTo(candidate).invariantSeparatorsPath }.sorted().toList()
            val summary = buildString {
                appendLine(if (existing == null) "请求注册新 Skill「${prepared.name}」" else "请求变更 Skill「${existing.name}」")
                appendLine("操作：${text("operation").ifBlank { operation }}")
                appendLine("候选目录：/workspace/.skill-proposals/$id")
                if (existing != null) appendLine("原版本：${existing.version}")
                appendLine("运行时：${prepared.runtime.name.lowercase(Locale.ROOT)}")
                prepared.entry?.let { appendLine("入口：$it") }
                appendLine("权限：${(if (!prepared.permissionsDeclared) "全部内置运行时命令（未声明，兼容模式）" else prepared.permissions.joinToString().ifEmpty { "无命令权限" })}")
                appendLine("清单版本：${SkillReviewPolicy.MANIFEST_VERSION}；审查策略版本：${SkillReviewPolicy.VERSION}")
                appendLine("文件：\n${files.joinToString("\n")}")
                appendLine("批准后生效")
                append("候选哈希：${hashTree(candidate)}")
            }
            val request = Request(
                id = id,
                conversationId = conversationId,
                operation = operation,
                skillId = targetId,
                name = prepared.name,
                runtime = prepared.runtime.name.lowercase(Locale.ROOT),
                entry = prepared.entry,
                permissions = prepared.permissions,
                permissionsDeclared = prepared.permissionsDeclared,
                manifestVersion = SkillReviewPolicy.MANIFEST_VERSION,
                reviewPolicyVersion = SkillReviewPolicy.VERSION,
                expectedVersion = existing?.version,
                candidate = candidate.canonicalPath,
                digest = hashTree(candidate),
                summary = summary,
            )
            save(state.copy(pending = request))
            return request
        } catch (error: Exception) { candidate.deleteRecursively(); throw error }
        finally { staging.deleteRecursively() }
    }

    @Synchronized
    fun decide(id: String, decision: String): Outcome {
        val request = state.pending ?: error("当前没有待处理事项")
        require(request.id == id) { "待处理事项已变化" }
        return when (decision) {
            "deferred" -> {
                val outcome = Outcome(id, request.conversationId, decision, "用户选择再议 Skill「${request.name}」；尚未生效，保留唯一待办，可继续讨论并检查候选文件")
                save(state.copy(pending = request.copy(deferred = true), outcomes = state.outcomes + outcome))
                outcome
            }
            "rejected" -> finish(request, decision, "用户拒绝${if (request.operation == "skill.edit") "变更" else "注册"} Skill「${request.name}」；未生效")
            "approved" -> {
                require(request.manifestVersion == SkillReviewPolicy.MANIFEST_VERSION && request.reviewPolicyVersion == SkillReviewPolicy.VERSION) { "审批策略已更新，请拒绝旧待办并重新提交" }
                val candidate = File(request.candidate)
                require(hashTree(candidate) == request.digest) { "候选文件已变化。请拒绝当前事项后重新提交，不能批准未审阅的新内容" }
                save(state.copy(pending = request.copy(phase = "publishing")))
                try { registry.publish(request.skillId, candidate, request.expectedVersion, request.digest) }
                catch (error: Exception) { save(state.copy(pending = request)); throw error }
                finish(request, decision, "用户已批准 Skill「${request.name}」，注册或变更已生效")
            }
            else -> error("无效确认操作")
        }
    }

    @Synchronized fun outcomes() = state.outcomes.toList()
    @Synchronized fun acknowledge(id: String, status: String) = save(state.copy(outcomes = state.outcomes.filterNot { it.requestId == id && it.status == status }))

    fun reviewRequest(request: Request): SkillReviewRequest = SkillReviewRequest(
        requestId = request.id,
        operation = request.operation,
        skillId = request.skillId,
        name = request.name,
        runtime = request.runtime,
        entry = request.entry,
        permissions = request.permissions,
        permissionsDeclared = request.permissionsDeclared,
        manifestVersion = request.manifestVersion,
        reviewPolicyVersion = request.reviewPolicyVersion,
        candidateDigest = request.digest,
    )

    private fun finish(request: Request, status: String, message: String): Outcome {
        val outcome = Outcome(request.id, request.conversationId, status, message)
        save(state.copy(pending = null, outcomes = state.outcomes + outcome))
        return outcome
    }
    private fun save(next: State) {
        val temp = File(root, "skill-approval.json.tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(json.encodeToString(State.serializer(), next).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        state = next
        pending.value = next.pending
    }
    companion object {
        fun hashTree(root: File): String {
            require(root.isDirectory) { "候选目录不存在" }
            val digest = MessageDigest.getInstance("SHA-256")
            root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach {
                digest.update(it.relativeTo(root).invariantSeparatorsPath.toByteArray()); digest.update(0.toByte()); digest.update(it.readBytes())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
