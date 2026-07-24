package com.agent.voiceassistant.workspace

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

class WorkspaceRepository(context: Context) {
    data class Entry(
        val relativePath: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long,
        val mimeType: String?,
    ) {
        val virtualPath: String get() = "/workspace/$relativePath".trimEnd('/')
    }

    data class ImportedFile(
        val entry: Entry,
        val sourceMimeType: String?,
    )

    private val appContext = context.applicationContext
    private val root = AndroidExecutionEnv(appContext).workspaceRoot.canonicalFile

    fun list(relativeDirectory: String = ""): List<Entry> {
        val directory = resolve(relativeDirectory)
        require(directory.isDirectory) { "不是文件夹：$relativeDirectory" }
        return directory.listFiles().orEmpty()
            .filterNot { it.name.startsWith(CAMERA_PENDING_PREFIX) }
            .map(::entry)
            .sortedWith(compareBy<Entry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
    }

    fun entry(relativePath: String): Entry = entry(resolve(relativePath))

    fun file(relativePath: String): File = resolve(relativePath).also {
        require(it.isFile) { "文件不存在：$relativePath" }
    }

    fun importUri(uri: Uri): ImportedFile {
        require(uri.scheme == "content" || uri.scheme == "file") { "只支持本地文件 URI" }
        val metadata = queryMetadata(uri)
        require(metadata.size == null || metadata.size <= MAX_IMPORT_BYTES) { "文件超过 50 MB 限制" }
        val target = uniqueFile(sanitizeName(metadata.name ?: "imported-file"))
        var copied = 0L
        try {
            openImportStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_IMPORT_BYTES) { "文件超过 50 MB 限制" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("无法读取所选文件")
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        return ImportedFile(entry(target), metadata.mimeType)
    }

    fun createCameraTarget(): Pair<File, Uri> {
        val target = uniqueFile("${CAMERA_PENDING_PREFIX}${System.currentTimeMillis()}.jpg")
        target.createNewFile()
        return target to contentUri(target)
    }

    fun finalizeCameraTarget(file: File): Entry {
        require(file.canonicalFile.toPath().startsWith(root.toPath())) { "相机文件路径越界" }
        val final = uniqueFile("photo-${System.currentTimeMillis()}.jpg")
        require(file.renameTo(final)) { "无法保存照片" }
        return entry(final)
    }

    fun discardCameraTarget(file: File) {
        if (file.canonicalFile.toPath().startsWith(root.toPath())) file.delete()
    }

    fun contentUri(relativePath: String): Uri = contentUri(file(relativePath))

    fun mimeType(relativePath: String): String = mimeFor(file(relativePath)) ?: "application/octet-stream"

    fun canPreview(relativePath: String): Boolean {
        val file = file(relativePath)
        return file.extension.lowercase(Locale.ROOT) in PREVIEW_EXTENSIONS && file.length() <= MAX_PREVIEW_BYTES
    }

    fun canEdit(relativePath: String): Boolean {
        val file = file(relativePath)
        if (file.extension.lowercase(Locale.ROOT) !in EDITABLE_EXTENSIONS || file.length() > MAX_EDIT_BYTES) return false
        return runCatching {
            file.inputStream().use { input ->
                val sample = ByteArray(4_096)
                val count = input.read(sample)
                count <= 0 || sample.take(count).none { it == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    fun readPreview(relativePath: String): String {
        val file = file(relativePath)
        require(file.length() <= MAX_PREVIEW_BYTES) { "文件超过 2 MB 预览限制" }
        return file.readText(Charsets.UTF_8).also { require(!it.contains('\u0000')) { "不支持预览二进制文件" } }
    }

    fun resolveWebPath(relativePath: String): File = resolve(relativePath)

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(
        appContext,
        "${appContext.packageName}.files",
        file,
    )

    private fun resolve(relativePath: String): File {
        val normalized = relativePath.trim().replace('\\', '/').trimStart('/')
        require(!normalized.split('/').contains("..")) { "路径不能包含 .." }
        val target = File(root, normalized).canonicalFile
        require(target.toPath().startsWith(root.toPath())) { "路径越界" }
        return target
    }

    private fun entry(file: File): Entry {
        val relative = root.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
        return Entry(relative, file.name, file.isDirectory, if (file.isFile) file.length() else 0L, file.lastModified(), mimeFor(file))
    }

    private fun uniqueFile(requestedName: String): File {
        val base = requestedName.substringBeforeLast('.', requestedName).ifBlank { "file" }
        val extension = requestedName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        var candidate = File(root, requestedName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(root, "$base-$suffix${extension?.let { ".$it" }.orEmpty()}")
            suffix++
        }
        return candidate
    }

    private fun sanitizeName(name: String): String = name
        .substringAfterLast('/')
        .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
        .trim()
        .take(120)
        .ifBlank { "imported-file" }

    private fun queryMetadata(uri: Uri): Metadata {
        var name: String? = null
        var size: Long? = null
        if (uri.scheme == "content") {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return Metadata(name, size, appContext.contentResolver.getType(uri))
    }

    fun readEditable(relativePath: String): String {
        val file = file(relativePath)
        require(canEdit(relativePath)) { "该文件不支持文本编辑" }
        return file.readText(Charsets.UTF_8)
    }

    fun saveText(relativePath: String, content: String): Long {
        val target = file(relativePath)
        require(canEdit(relativePath)) { "该文件不支持文本编辑" }
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_EDIT_BYTES) { "文件超过编辑大小限制" }
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
        return target.lastModified()
    }

    /**
     * Some older sharing apps still expose a raw file:// URI. On modern Android,
     * resolving its indexed MediaStore row gives us a permission-aware content URI.
     */
    private fun openImportStream(uri: Uri): InputStream? {
        if (uri.scheme != "file") return appContext.contentResolver.openInputStream(uri)
        val path = uri.path ?: return null
        val direct = runCatching { File(path).inputStream() }.getOrNull()
        if (direct != null) return direct
        val contentUri = findMediaStoreUri(path) ?: return null
        return appContext.contentResolver.openInputStream(contentUri)
    }

    private fun findMediaStoreUri(path: String): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DATA} = ?"
        return runCatching {
            appContext.contentResolver.query(collection, projection, selection, arrayOf(path), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun mimeFor(file: File): String? = when (file.extension.lowercase(Locale.ROOT)) {
        "txt", "log", "md", "markdown", "json", "xml", "csv", "kt", "java", "py", "js", "ts", "sh" -> "text/plain"
        "html", "htm" -> "text/html"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> null
    }

    private data class Metadata(val name: String?, val size: Long?, val mimeType: String?)

    companion object {
        private const val MAX_IMPORT_BYTES = 50L * 1_024 * 1_024
        private const val MAX_PREVIEW_BYTES = 2L * 1_024 * 1_024
        private const val MAX_EDIT_BYTES = 2L * 1_024 * 1_024
        private const val CAMERA_PENDING_PREFIX = ".camera-"
        private val PREVIEW_EXTENSIONS = setOf("txt", "log", "md", "markdown", "json", "xml", "csv", "html", "htm", "kt", "java", "py", "js", "ts", "sh")
        private val EDITABLE_EXTENSIONS = PREVIEW_EXTENSIONS + setOf(
            "yaml", "yml", "css", "properties", "toml", "ini",
        )
    }
}
