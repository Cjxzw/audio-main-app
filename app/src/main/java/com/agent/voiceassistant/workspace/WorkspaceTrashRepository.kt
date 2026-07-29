package com.agent.voiceassistant.workspace

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class WorkspaceTrashRepository(context: Context) {
    @Serializable
    data class TrashEntry(
        val id: String,
        val originalPath: String,
        val storedName: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val deletedAt: Long,
        val conversationId: String? = null,
        val taskId: String? = null,
    )

    private val appContext = context.applicationContext
    private val workspaceRoot = File(appContext.filesDir, "agent-runtime/workspace").apply { mkdirs() }.canonicalFile
    private val trashRoot = File(appContext.filesDir, "agent-runtime/workspace-trash").apply { mkdirs() }
    private val dataRoot = File(trashRoot, "data").apply { mkdirs() }
    private val indexFile = File(trashRoot, "index.json")
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    init {
        synchronized(lock) { cleanupExpiredLocked() }
    }

    fun moveAgentPathToTrash(
        virtualPath: String,
        conversationId: String? = null,
        taskId: String? = null,
    ): TrashEntry = synchronized(lock) {
        cleanupExpiredLocked()
        val relative = normalizeVirtualPath(virtualPath)
        val source = resolveWorkspace(relative)
        require(source.exists()) { "路径不存在：$virtualPath" }
        require(source != workspaceRoot) { "不能删除工作区根目录" }
        val id = UUID.randomUUID().toString()
        val storedName = "$id-${sanitizeName(source.name)}"
        val target = File(dataRoot, storedName)
        move(source, target)
        val entry = TrashEntry(
            id = id,
            originalPath = relative,
            storedName = storedName,
            name = source.name,
            isDirectory = target.isDirectory,
            size = sizeOf(target),
            deletedAt = System.currentTimeMillis(),
            conversationId = conversationId,
            taskId = taskId,
        )
        saveLocked(loadLocked() + entry)
        preferences.edit().putBoolean(KEY_UNREAD, true).apply()
        entry
    }

    fun list(): List<TrashEntry> = synchronized(lock) {
        cleanupExpiredLocked()
        loadLocked().sortedByDescending(TrashEntry::deletedAt)
    }

    fun hasUnread(): Boolean = preferences.getBoolean(KEY_UNREAD, false) && list().isNotEmpty()

    fun markSeen() {
        preferences.edit().putBoolean(KEY_UNREAD, false).apply()
    }

    fun restore(id: String): String = synchronized(lock) {
        cleanupExpiredLocked()
        val entries = loadLocked()
        val entry = entries.firstOrNull { it.id == id } ?: error("回收内容不存在")
        val source = File(dataRoot, entry.storedName)
        require(source.exists()) { "回收文件已经丢失" }
        val requested = resolveWorkspace(entry.originalPath)
        val target = uniqueRestoreTarget(requested)
        target.parentFile?.mkdirs()
        move(source, target)
        saveLocked(entries.filterNot { it.id == id })
        workspaceRoot.toPath().relativize(target.toPath()).toString().replace('\\', '/')
    }

    fun deletePermanently(id: String) = synchronized(lock) {
        val entries = loadLocked()
        val entry = entries.firstOrNull { it.id == id } ?: return@synchronized
        delete(File(dataRoot, entry.storedName))
        saveLocked(entries.filterNot { it.id == id })
    }

    fun file(id: String): File = synchronized(lock) {
        val entry = loadLocked().firstOrNull { it.id == id } ?: error("回收内容不存在")
        File(dataRoot, entry.storedName).also { require(it.isFile) { "回收文件不存在" } }
    }

    fun contentUri(id: String): Uri = FileProvider.getUriForFile(
        appContext,
        "${appContext.packageName}.files",
        file(id),
    )

    fun mimeType(entry: TrashEntry): String = WorkspaceMimeTypes.forName(entry.name)

    fun canPreview(entry: TrashEntry): Boolean =
        !entry.isDirectory && WorkspaceMimeTypes.canPreview(entry.name) && file(entry.id).length() <= MAX_PREVIEW_BYTES

    fun readPreview(entry: TrashEntry): String {
        require(canPreview(entry)) { "该文件不支持应用内预览" }
        return file(entry.id).readText(Charsets.UTF_8).also {
            require(!it.contains('\u0000')) { "不支持预览二进制文件" }
        }
    }

    private fun cleanupExpiredLocked(now: Long = System.currentTimeMillis()) {
        val entries = loadLocked()
        val expired = entries.filter { now - it.deletedAt >= RETENTION_MS }
        if (expired.isEmpty()) return
        expired.forEach { delete(File(dataRoot, it.storedName)) }
        saveLocked(entries.filterNot { it in expired })
    }

    private fun loadLocked(): List<TrashEntry> = if (!indexFile.isFile) {
        emptyList()
    } else {
        runCatching { json.decodeFromString<List<TrashEntry>>(indexFile.readText()) }.getOrDefault(emptyList())
    }

    private fun saveLocked(entries: List<TrashEntry>) {
        val temporary = File(trashRoot, "index.json.tmp")
        temporary.writeText(json.encodeToString(entries), Charsets.UTF_8)
        Files.move(temporary.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun normalizeVirtualPath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        require(normalized == "/workspace" || normalized.startsWith("/workspace/")) { "只能回收 /workspace 中的内容" }
        require(normalized != "/workspace") { "不能删除工作区根目录" }
        val relative = normalized.removePrefix("/workspace/").trim('/')
        require(relative.isNotBlank() && ".." !in relative.split('/')) { "路径无效：$path" }
        return relative
    }

    private fun resolveWorkspace(relative: String): File = File(workspaceRoot, relative).canonicalFile.also {
        require(it.toPath().startsWith(workspaceRoot.toPath())) { "路径越界" }
    }

    private fun uniqueRestoreTarget(requested: File): File {
        if (!requested.exists()) return requested
        val base = requested.nameWithoutExtension.ifBlank { requested.name }
        val extension = requested.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        var index = 1
        while (true) {
            val suffix = if (index == 1) "（已还原）" else "（已还原$index）"
            File(requested.parentFile, "$base$suffix$extension").also { if (!it.exists()) return it }
            index++
        }
    }

    private fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        if (source.isDirectory) source.copyRecursively(target, overwrite = false) else source.copyTo(target, overwrite = false)
        delete(source)
    }

    private fun delete(file: File) {
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    private fun sizeOf(file: File): Long = if (file.isFile) file.length() else file.walkTopDown().filter(File::isFile).sumOf(File::length)

    private fun sanitizeName(name: String): String = name.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").take(100)

    companion object {
        private const val PREFERENCES = "workspace_trash"
        private const val KEY_UNREAD = "has_unread"
        private const val MAX_PREVIEW_BYTES = 2L * 1024 * 1024
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1_000
    }
}

internal object WorkspaceMimeTypes {
    private val previewExtensions = setOf("txt", "log", "md", "markdown", "json", "xml", "csv", "html", "htm", "kt", "java", "py", "js", "ts", "sh")

    fun canPreview(name: String) = name.substringAfterLast('.', "").lowercase() in previewExtensions

    fun forName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md", "markdown", "json", "xml", "csv", "kt", "java", "py", "js", "ts", "sh" -> "text/plain"
        "html", "htm" -> "text/html"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
