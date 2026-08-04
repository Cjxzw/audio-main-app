package com.agent.voiceassistant.agent.runtime

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

class SkillRegistry(
    private val skillsRoot: File,
    private val disabledSkillsRoot: File = File(skillsRoot.parentFile, "skills-disabled"),
    private val deletedManifest: File = File(skillsRoot.parentFile, "skills-deleted"),
    private val modifiedManifest: File = File(skillsRoot.parentFile, "skills-user-modified"),
    private val systemSkillsRoot: File = File(skillsRoot.parentFile, "system-skills"),
    private val disabledSystemManifest: File = File(skillsRoot.parentFile, "system-skills-disabled"),
) {
    enum class Residency { TURN, CONVERSATION }

    data class Skill(
        val id: String,
        val name: String,
        val description: String,
        val virtualPath: String,
        val enabled: Boolean,
        val bundled: Boolean = false,
        val system: Boolean = false,
        val residency: Residency = Residency.CONVERSATION,
        val version: String = "",
    )

    data class Registration(
        val skill: Skill,
        val compatibilityNotes: String,
    )

    data class SkillFile(
        val relativePath: String,
        val size: Long,
        val modifiedAt: Long,
        val editable: Boolean,
        val sha256: String,
    )

    data class UseResult(
        val skill: Skill,
        val resourceName: String,
        val content: String,
        val resources: List<SkillFile>,
    )

    init {
        skillsRoot.mkdirs()
        disabledSkillsRoot.mkdirs()
        systemSkillsRoot.mkdirs()
    }

    fun list(): List<Skill> = listAll().filter(Skill::enabled)

    fun listAll(): List<Skill> = buildList {
        skillsRoot.listFiles().orEmpty().filter(File::isDirectory).mapNotNullTo(this) {
            loadSkill(it, enabled = true)
        }
        disabledSkillsRoot.listFiles().orEmpty().filter(File::isDirectory).mapNotNullTo(this) {
            loadSkill(it, enabled = false)
        }
        systemSkillsRoot.listFiles().orEmpty().filter(File::isDirectory).mapNotNullTo(this) {
            loadSkill(it, enabled = it.name !in disabledSystemIds(), system = true)
        }
    }.distinctBy(Skill::id).sortedBy { it.name.lowercase() }

    fun promptSummary(): String {
        val skills = list()
        if (skills.isEmpty()) return "当前没有启用的 Skill。"
        return buildString {
            appendLine("可用 Skill（只注入索引；需要使用时调用 skill_use）：")
            skills.forEach { skill ->
                appendLine("- ${skill.name}: ${skill.description}（${skill.residency.name}）")
            }
        }.trimEnd()
    }

    fun setEnabled(id: String, enabled: Boolean): Skill? {
        val safeId = requireSafeId(id)
        listAll().firstOrNull { it.id == safeId && it.system }?.let { system ->
            val disabled = disabledSystemIds().toMutableSet()
            if (enabled) disabled.remove(safeId) else disabled.add(safeId)
            writeIds(disabledSystemManifest, disabled)
            return system.copy(enabled = enabled)
        }
        val from = File(if (enabled) disabledSkillsRoot else skillsRoot, safeId)
        val to = File(if (enabled) skillsRoot else disabledSkillsRoot, safeId)
        if (!from.isDirectory) return listAll().firstOrNull { it.id == safeId && it.enabled == enabled }
        require(!to.exists()) { "Skill 目标目录已存在：$safeId" }
        require(from.renameTo(to)) { "无法${if (enabled) "启用" else "停用"} Skill：$safeId" }
        return loadSkill(to, enabled)
    }

    fun delete(id: String): Boolean {
        val safeId = requireSafeId(id)
        require(listAll().none { it.id == safeId && it.system }) { "系统 Skill 不能删除" }
        val targets = listOf(File(skillsRoot, safeId), File(disabledSkillsRoot, safeId))
        val existed = targets.any(File::exists)
        targets.forEach { it.deleteRecursively() }
        if (existed) {
            val deleted = deletedIds().toMutableSet().apply { add(safeId) }
            deletedManifest.writeText(deleted.sorted().joinToString("\n"))
            writeIds(modifiedManifest, readIds(modifiedManifest) - safeId)
        }
        return existed
    }

    fun create(name: String, description: String, content: String? = null): Skill {
        val normalizedName = name.trim()
        val normalizedDescription = description.trim()
        require(normalizedName.isNotBlank()) { "名称不能为空" }
        require(normalizedDescription.isNotBlank()) { "简介不能为空" }
        requireUniqueName(normalizedName)
        val id = uniqueId(slug(normalizedName).ifBlank { "skill" })
        val target = File(skillsRoot, id)
        require(target.mkdirs()) { "无法创建 Skill：$id" }
        return try {
            val body = content?.trim()?.takeIf(String::isNotBlank)
                ?: "# $normalizedName\n\n请在此描述该技能的适用场景、执行流程和输出要求。"
            require(body.toByteArray(Charsets.UTF_8).size <= MAX_CORE_BYTES) { "Skill 核心文件过大" }
            File(target, "SKILL.md").writeText(
                renderSkill(normalizedName, normalizedDescription, body),
                Charsets.UTF_8,
            )
            markModified(id)
            removeDeletedTombstone(id)
            loadSkill(target, enabled = true) ?: error("Skill 创建后无法解析")
        } catch (error: Exception) {
            target.deleteRecursively()
            throw error
        }
    }

    fun update(id: String, name: String, description: String, body: String): Skill {
        val current = listAll().firstOrNull { it.id == id } ?: error("Skill 不存在：$id")
        require(!current.system) { "系统 Skill 不能编辑" }
        requireUniqueName(name, current.id)
        val root = File(if (current.enabled) skillsRoot else disabledSkillsRoot, current.id)
        val core = File(root, "SKILL.md")
        require(body.toByteArray().size <= MAX_CORE_BYTES) { "Skill 核心文件过大" }
        core.writeText(renderSkill(name, description, body), Charsets.UTF_8)
        writeIds(modifiedManifest, readIds(modifiedManifest) + current.id)
        return loadSkill(root, current.enabled) ?: error("Skill 更新后无法解析")
    }

    fun updateMetadata(id: String, name: String, description: String): Skill {
        require(name.isNotBlank()) { "名称不能为空" }
        require(description.isNotBlank()) { "简介不能为空" }
        val current = requireSkill(id)
        require(!current.system) { "系统 Skill 不能编辑" }
        requireUniqueName(name, current.id)
        val root = skillRoot(current)
        val core = File(root, "SKILL.md")
        atomicWrite(core, renderSkill(name, description, stripFrontMatter(core.readText(Charsets.UTF_8))))
        markModified(current.id)
        return loadSkill(root, current.enabled) ?: error("Skill 更新后无法解析")
    }

    fun files(id: String): List<SkillFile> {
        val root = skillRoot(requireSkill(id)).canonicalFile
        return root.walkTopDown()
            .filter(File::isFile)
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .map { file ->
                SkillFile(
                    relativePath = file.relativeTo(root).path.replace('\\', '/'),
                    size = file.length(),
                    modifiedAt = file.lastModified(),
                    editable = isEditableText(file),
                    sha256 = sha256(file.readBytes()),
                )
            }
            .sortedWith(compareBy<SkillFile>({ it.relativePath != "SKILL.md" }, { it.relativePath.lowercase(Locale.ROOT) }))
            .toList()
    }

    fun readFile(id: String, relativePath: String): String {
        val file = resolveSkillFile(id, relativePath)
        require(isEditableText(file)) { "该文件不支持文本编辑" }
        return file.readText(Charsets.UTF_8)
    }

    fun updateFile(id: String, relativePath: String, content: String): Long {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "文件超过编辑大小限制" }
        val current = requireSkill(id)
        require(!current.system) { "系统 Skill 不能编辑" }
        val file = resolveSkillFile(current, relativePath)
        require(isEditableText(file)) { "该文件不支持文本编辑" }
        if (file.name == "SKILL.md") validateCoreContent(current, content)
        atomicWrite(file, content)
        if (file.name == "SKILL.md") {
            require(loadSkill(skillRoot(current), current.enabled) != null) { "SKILL.md 保存后无法解析" }
        }
        markModified(current.id)
        return file.lastModified()
    }

    fun coreBody(id: String): String {
        val current = listAll().firstOrNull { it.id == id } ?: error("Skill 不存在：$id")
        val root = skillRoot(current)
        return stripFrontMatter(File(root, "SKILL.md").readText(Charsets.UTF_8))
    }

    private fun requireSkill(id: String): Skill {
        val safeId = requireSafeId(id)
        return listAll().firstOrNull { it.id == safeId } ?: error("Skill 不存在：$safeId")
    }

    private fun skillRoot(skill: Skill): File = if (skill.system) {
        File(systemSkillsRoot, skill.id)
    } else {
        File(if (skill.enabled) skillsRoot else disabledSkillsRoot, skill.id)
    }

    fun use(skillName: String, resourceName: String? = null): UseResult {
        val normalized = normalizeName(skillName)
        val skill = listAll().singleOrNull { normalizeName(it.name) == normalized }
            ?: error("没有找到名称为 '$skillName' 的 Skill")
        require(skill.enabled) { "Skill '${skill.name}' 已停用" }
        val requested = resourceName?.trim()?.takeIf(String::isNotBlank) ?: "SKILL.md"
        val file = resolveSkillFile(skill, requested)
        require(isEditableText(file)) { "skill_use 只能加载 UTF-8 文本文件" }
        return UseResult(skill, requested, file.readText(Charsets.UTF_8), files(skill.id))
    }

    fun edit(
        skillName: String,
        operation: String,
        resourceName: String,
        expectedSha256: String,
        oldText: String? = null,
        newText: String? = null,
    ): Skill {
        val skill = listAll().singleOrNull { normalizeName(it.name) == normalizeName(skillName) }
            ?: error("没有找到名称为 '$skillName' 的 Skill")
        require(!skill.system) { "系统 Skill 不能编辑" }
        val resource = resourceName.trim().ifBlank { "SKILL.md" }
        val root = skillRoot(skill).canonicalFile
        val normalized = resource.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && ".." !in normalized.split('/')) { "无效文件路径" }
        val target = File(root, normalized).canonicalFile
        require(target.toPath().startsWith(root.toPath())) { "Skill 文件路径越界" }
        newText?.let { require(it.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "文件超过编辑大小限制" } }
        when (operation) {
            "rename" -> {
                require(expectedSha256 == skill.version) { "Skill 已变化，请重新 skill_use 后再编辑" }
                return updateMetadata(skill.id, newText.orEmpty(), skill.description)
            }
            "create_resource" -> {
                require(expectedSha256 == skill.version) { "Skill 已变化，请重新 skill_use 后再编辑" }
                require(!target.exists() && normalized != "SKILL.md") { "资源已存在或名称无效" }
                require(target.extension.lowercase(Locale.ROOT) in EDITABLE_EXTENSIONS) { "只允许创建文本资源" }
                target.parentFile?.mkdirs()
                atomicWrite(target, newText.orEmpty())
            }
            "replace_text", "replace_resource", "delete_resource" -> {
                require(target.isFile && !Files.isSymbolicLink(target.toPath())) { "Skill 文件不存在" }
                require(sha256(target.readBytes()) == expectedSha256) { "文件已变化，请重新 skill_use 后再编辑" }
                require(isEditableText(target)) { "只允许编辑 UTF-8 文本资源" }
                when (operation) {
                    "replace_text" -> {
                        val old = oldText.orEmpty()
                        require(old.isNotEmpty()) { "replace_text 缺少 old_text" }
                        val current = target.readText(Charsets.UTF_8)
                        require(current.windowed(old.length, 1).count { it == old } == 1) { "old_text 必须唯一匹配" }
                        val next = current.replace(old, newText.orEmpty())
                        if (normalized == "SKILL.md") validateCoreContent(skill, next)
                        atomicWrite(target, next)
                    }
                    "replace_resource" -> {
                        val next = newText.orEmpty()
                        if (normalized == "SKILL.md") validateCoreContent(skill, next)
                        atomicWrite(target, next)
                    }
                    else -> {
                        require(normalized != "SKILL.md") { "不能删除 SKILL.md" }
                        require(target.delete()) { "无法删除 Skill 资源" }
                    }
                }
            }
            else -> error("不支持的 skill_edit operation：$operation")
        }
        markModified(skill.id)
        return requireSkill(skill.id)
    }

    private fun resolveSkillFile(id: String, relativePath: String): File =
        resolveSkillFile(requireSkill(id), relativePath)

    private fun resolveSkillFile(skill: Skill, relativePath: String): File {
        val normalized = relativePath.trim().replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && ".." !in normalized.split('/')) { "无效文件路径" }
        val root = skillRoot(skill).canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.toPath().startsWith(root.toPath()) && target.isFile) { "Skill 文件不存在" }
        require(!Files.isSymbolicLink(target.toPath())) { "不支持编辑符号链接" }
        return target
    }

    private fun isEditableText(file: File): Boolean {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return false
        if (file.extension.lowercase(Locale.ROOT) !in EDITABLE_EXTENSIONS) return false
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.any { it == 0.toByte() }) return@runCatching false
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)
    }

    private fun atomicWrite(target: File, content: String) {
        val temporary = File(target.parentFile, ".${target.name}.editing")
        try {
            temporary.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun markModified(id: String) {
        writeIds(modifiedManifest, readIds(modifiedManifest) + id)
    }

    fun unavailableReason(virtualPath: String): String? {
        val id = virtualPath.removePrefix("/skills/").substringBefore('/').takeIf { it.isNotBlank() }
            ?: return null
        return when {
            File(disabledSkillsRoot, id).exists() -> "Skill '$id' 已停用，当前会话不会再加载其文件。"
            id in deletedIds() -> "Skill '$id' 已删除，当前会话不会再加载其文件。"
            else -> null
        }
    }

    fun registerFromWorkspace(
        workspaceRoot: File,
        sourcePath: String,
        name: String,
        description: String,
        coreFile: String,
        compatibilityNotes: String,
        reviewedFiles: List<String>,
    ): Registration {
        requireUniqueName(name)
        val source = File(workspaceRoot, sourcePath.removePrefix("/workspace/")).canonicalFile
        require(source.toPath().startsWith(workspaceRoot.canonicalFile.toPath())) { "Skill 来源必须位于 /workspace" }
        require(source != workspaceRoot.canonicalFile) { "不能将整个工作区注册为 Skill" }
        require(source.exists()) { "Skill 来源不存在：$sourcePath" }
        val sourceRoot = if (source.isDirectory) source else source.parentFile
        val files = if (source.isDirectory) source.walkTopDown().filter(File::isFile).toList() else listOf(source)
        require(files.isNotEmpty()) { "Skill 目录为空" }
        require(files.size <= MAX_FILES) { "Skill 文件过多，最多 $MAX_FILES 个" }
        require(files.none { Files.isSymbolicLink(it.toPath()) }) { "Skill 不允许包含符号链接" }
        require(files.sumOf(File::length) <= MAX_TOTAL_BYTES) { "Skill 总大小超过限制" }
        val scripts = files.filter { it.extension.lowercase(Locale.ROOT) in SCRIPT_EXTENSIONS }
        require(scripts.isEmpty()) {
            "该 Skill 包含不受支持的脚本：${scripts.joinToString { it.name }}。请先改写为说明流程或内置工具调用。"
        }
        files.forEach { file ->
            require(file.length() <= MAX_FILE_BYTES) { "文件过大：${file.name}" }
            val bytes = file.readBytes()
            require(bytes.none { it == 0.toByte() }) { "Skill 不支持二进制文件：${file.name}" }
        }
        val actualFiles = files.map { it.relativeTo(sourceRoot).path.replace('\\', '/') }.toSet()
        val reviewed = reviewedFiles.map { it.trim().replace('\\', '/').trimStart('/') }.filter(String::isNotBlank).toSet()
        require(reviewed == actualFiles) {
            "reviewed_files 必须完整列出候选 Skill 的全部文件；尚未确认：${(actualFiles - reviewed).joinToString()}"
        }

        val requestedCore = File(sourceRoot, coreFile).canonicalFile
        require(requestedCore.toPath().startsWith(sourceRoot.canonicalFile.toPath()) && requestedCore.isFile) {
            "核心文件不存在或路径越界：$coreFile"
        }
        val id = uniqueId(slug(name).ifBlank { slug(source.nameWithoutExtension) }.ifBlank { "skill" })
        val target = File(skillsRoot, id)
        require(!target.exists()) { "Skill 已存在：$id" }
        if (source.isDirectory) {
            require(source.renameTo(target)) { "无法将 Skill 移出工作区" }
        } else {
            target.mkdirs()
            source.copyTo(File(target, source.name), overwrite = false)
            source.delete()
        }
        val movedCore = File(target, requestedCore.relativeTo(sourceRoot).path)
        val standardCore = File(target, "SKILL.md")
        val body = stripFrontMatter(movedCore.readText(Charsets.UTF_8))
        standardCore.writeText(renderSkill(name, description, body), Charsets.UTF_8)
        if (movedCore != standardCore) movedCore.delete()
        removeDeletedTombstone(id)
        val skill = loadSkill(target, enabled = true) ?: error("Skill 注册后无法解析")
        return Registration(skill, compatibilityNotes.take(1_000))
    }

    private fun loadSkill(directory: File, enabled: Boolean, system: Boolean = false): Skill? {
        val file = File(directory, "SKILL.md")
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val metadata = parseFrontMatter(text)
        val name = metadata["name"]?.takeIf(String::isNotBlank) ?: directory.name
        val description = metadata["description"]?.takeIf(String::isNotBlank)
            ?: stripFrontMatter(text).lineSequence().firstOrNull { it.isNotBlank() }?.removePrefix("#")?.trim()
            ?: "按需加载的本地工作流"
        return Skill(
            id = directory.name,
            name = name.take(80),
            description = description.take(300),
            virtualPath = "/skills/${directory.name}/SKILL.md",
            enabled = enabled,
            system = system,
            residency = if (system) Residency.TURN else Residency.CONVERSATION,
            version = skillVersion(directory),
        )
    }

    private fun renderSkill(name: String, description: String, body: String): String = buildString {
        appendLine("---")
        appendLine("name: ${name.trim().take(80)}")
        appendLine("description: ${description.trim().replace('\n', ' ').take(300)}")
        appendLine("---")
        appendLine()
        append(body.trim())
        appendLine()
    }

    private fun stripFrontMatter(text: String): String {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return text.trim()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (end < 0) text.trim() else lines.drop(end + 2).joinToString("\n").trim()
    }

    private fun parseFrontMatter(text: String): Map<String, String> {
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return emptyMap()
        return lines.subList(1, end + 1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) return@mapNotNull null
            val key = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim().trim('"', '\'')
            if (key in setOf("name", "description")) key to value else null
        }.toMap()
    }

    private fun uniqueId(base: String): String {
        var candidate = base
        var suffix = 2
        while (File(skillsRoot, candidate).exists() || File(disabledSkillsRoot, candidate).exists()) {
            candidate = "$base-$suffix"
            suffix++
        }
        return candidate
    }

    private fun requireUniqueName(name: String, exceptId: String? = null) {
        val normalized = normalizeName(name)
        require(normalized.isNotBlank()) { "名称不能为空" }
        require(listAll().none { it.id != exceptId && normalizeName(it.name) == normalized }) {
            "Skill 名称已存在：${name.trim()}"
        }
    }

    private fun validateCoreContent(skill: Skill, content: String) {
        val metadata = parseFrontMatter(content)
        val name = metadata["name"]?.takeIf(String::isNotBlank) ?: skill.id
        requireUniqueName(name, skill.id)
    }

    private fun normalizeName(value: String): String = Normalizer.normalize(
        value.trim().replace(Regex("\\s+"), " "),
        Normalizer.Form.NFC,
    ).lowercase(Locale.ROOT)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    private fun skillVersion(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.walkTopDown().filter(File::isFile)
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.relativeTo(directory).path.replace('\\', '/') }
            .forEach { file ->
                digest.update(file.relativeTo(directory).path.replace('\\', '/').toByteArray(Charsets.UTF_8))
                digest.update(byteArrayOf(0))
                digest.update(file.readBytes())
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun slug(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .take(64)

    private fun requireSafeId(id: String): String = id.also {
        require(it.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "无效 Skill ID" }
    }

    private fun deletedIds(): Set<String> = readIds(deletedManifest)
    private fun disabledSystemIds(): Set<String> = readIds(disabledSystemManifest)

    private fun removeDeletedTombstone(id: String) {
        val remaining = deletedIds() - id
        writeIds(deletedManifest, remaining)
    }

    private fun readIds(file: File): Set<String> = file.takeIf(File::isFile)
        ?.readLines().orEmpty().map(String::trim).filter(String::isNotBlank).toSet()

    private fun writeIds(file: File, ids: Set<String>) {
        file.writeText(ids.sorted().joinToString("\n"))
    }

    private companion object {
        const val MAX_FILES = 50
        const val MAX_FILE_BYTES = 512 * 1_024L
        const val MAX_CORE_BYTES = 256 * 1_024
        const val MAX_TOTAL_BYTES = 2 * 1_024 * 1_024L
        val SCRIPT_EXTENSIONS = setOf("py", "sh", "js", "ts", "jar", "class", "dex", "so", "exe", "bat", "cmd", "ps1")
        val EDITABLE_EXTENSIONS = setOf(
            "md", "markdown", "txt", "json", "xml", "yaml", "yml", "csv", "html", "htm",
            "kt", "java", "js", "ts", "css", "properties", "toml", "ini",
        )
    }
}
