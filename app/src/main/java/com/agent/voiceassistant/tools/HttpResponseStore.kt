package com.agent.voiceassistant.tools

import okio.Buffer
import okio.BufferedSource
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Streams HTTP responses without putting large bodies in the model context or the heap.
 * Auto-cached files are isolated in a directory owned by this class and expire lazily.
 */
internal class HttpResponseStore(
    private val workspaceRoot: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Artifact(
        val virtualPath: String,
        val bytes: Long,
        val sha256: String,
        val truncated: Boolean,
        val expiresAt: Long?,
    )

    data class Capture(
        val preview: ByteArray,
        val displayTruncated: Boolean,
        val isTextual: Boolean,
        val artifact: Artifact? = null,
    )

    private val cacheRoot = File(workspaceRoot, CACHE_DIRECTORY).apply { mkdirs() }

    init {
        cleanup()
    }

    fun cleanup() {
        val expiry = now() - CACHE_TTL_MS
        cacheRoot.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() in 1..expiry) file.delete()
        }
    }

    fun capture(
        source: BufferedSource,
        contentLength: Long?,
        contentType: String?,
        downloadTarget: File? = null,
        downloadVirtualPath: String? = null,
    ): Capture {
        cleanup()
        require(contentLength == null || contentLength <= MAX_FILE_BYTES || downloadTarget == null) {
            "下载文件超过 ${MAX_FILE_BYTES / BYTES_PER_MIB} MiB 上限"
        }
        val textual = isTextual(contentType)
        val explicitDownload = downloadTarget != null
        val shouldStartArtifact = explicitDownload || contentLength?.let { it > MAX_PREVIEW_BYTES } == true || !textual
        var virtualPath = if (explicitDownload) null else if (shouldStartArtifact) cacheVirtualPath(contentType) else null
        var target = downloadTarget ?: virtualPath?.let { File(cacheRoot, it.substringAfterLast('/')) }
        var temporary = target?.let { File(it.parentFile, ".${it.name}.${UUID.randomUUID()}.part") }
        var output: BufferedOutputStream? = null
        var persisting = false
        var digest: MessageDigest? = null
        var artifactBytes = 0L
        var artifactTruncated = false
        val preview = ByteArrayOutputStream(minOf(MAX_PREVIEW_BYTES.toInt(), 8 * 1024))
        var totalBytes = 0L

        fun startOutput() {
            if (output != null) return
            if (temporary == null) {
                virtualPath = cacheVirtualPath(contentType)
                target = File(cacheRoot, virtualPath!!.substringAfterLast('/'))
                temporary = File(target!!.parentFile, ".${target!!.name}.${UUID.randomUUID()}.part")
            }
            val part = requireNotNull(temporary)
            part.parentFile?.mkdirs()
            output = BufferedOutputStream(FileOutputStream(part))
            digest = MessageDigest.getInstance("SHA-256")
            persisting = true
        }

        fun writeArtifact(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            if (length <= 0) return
            startOutput()
            output!!.write(bytes, offset, length)
            digest!!.update(bytes, offset, length)
            artifactBytes += length
        }

        // A requested download should still produce an empty file for an empty response.
        // Starting the temporary output before reading also keeps that operation atomic.
        if (explicitDownload) startOutput()

        try {
            while (true) {
                val remaining = MAX_FILE_BYTES - totalBytes
                if (remaining < 0L) {
                    artifactTruncated = true
                    break
                }
                // Read one byte beyond the cap for auto-cache mode, so a response that is
                // exactly the cap is not incorrectly marked truncated when it ends at EOF.
                val requestSize = minOf(8_192L, remaining + 1L)
                if (requestSize <= 0L) {
                    error("HTTP 响应超过 ${MAX_FILE_BYTES / BYTES_PER_MIB} MiB 上限")
                }
                val chunk = Buffer()
                val count = source.read(chunk, requestSize)
                if (count < 0L) {
                    break
                }
                val bytes = chunk.readByteArray()
                val allowed = minOf(bytes.size.toLong(), remaining).toInt()
                if (allowed < bytes.size) {
                    artifactTruncated = true
                    if (explicitDownload) error("下载文件超过 ${MAX_FILE_BYTES / BYTES_PER_MIB} MiB 上限")
                }

                val crossesPreview = !persisting && totalBytes + allowed > MAX_PREVIEW_BYTES
                if (crossesPreview) {
                    startOutput()
                    writeArtifact(preview.toByteArray())
                } else if (shouldStartArtifact) {
                    startOutput()
                }
                if (persisting) writeArtifact(bytes, 0, allowed)
                val previewRemaining = MAX_PREVIEW_BYTES - preview.size().toLong()
                if (previewRemaining > 0L) preview.write(bytes, 0, minOf(allowed.toLong(), previewRemaining).toInt())
                totalBytes += allowed
                if (artifactTruncated) break
            }
            output?.flush()
            output?.close()
            output = null
            val artifact = if (temporary != null && persisting) {
                requireNotNull(digest)
                val finalTarget = requireNotNull(target)
                if (finalTarget.exists() && !finalTarget.delete()) {
                    error("无法覆盖 HTTP 文件：${finalTarget.name}")
                }
                require(temporary.renameTo(finalTarget)) { "无法完成 HTTP 文件写入：${finalTarget.name}" }
                finalTarget.setLastModified(now())
                Artifact(
                    virtualPath = downloadVirtualPath ?: virtualPath ?: error("下载路径缺失"),
                    bytes = artifactBytes,
                    sha256 = digest!!.digest().joinToString("") { "%02x".format(it) },
                    truncated = artifactTruncated,
                    expiresAt = if (explicitDownload) null else now() + CACHE_TTL_MS,
                )
            } else {
                null
            }
            return Capture(preview.toByteArray(), totalBytes > MAX_PREVIEW_BYTES, textual, artifact)
        } catch (error: Throwable) {
            runCatching { output?.close() }
            temporary?.delete()
            throw error
        }
    }

    private fun cacheVirtualPath(contentType: String?): String {
        val extension = when {
            contentType.orEmpty().contains("json", true) -> "json"
            contentType.orEmpty().contains("xml", true) -> "xml"
            contentType.orEmpty().contains("html", true) -> "html"
            contentType.orEmpty().startsWith("text/") -> "txt"
            else -> "bin"
        }
        return "/workspace/$CACHE_DIRECTORY/http-${now()}-${UUID.randomUUID().toString().take(8)}.$extension"
    }

    private fun isTextual(contentType: String?): Boolean {
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
        if (type.isBlank()) return true
        return type.startsWith("text/") || type.contains("json") || type.contains("xml") ||
            type.contains("javascript") || type.contains("csv") || type.contains("yaml") ||
            type == "application/x-www-form-urlencoded"
    }

    companion object {
        const val CACHE_DIRECTORY = ".http-cache"
        const val CACHE_TTL_MS = 3L * 24 * 60 * 60 * 1_000
        const val MAX_PREVIEW_BYTES = 512L * 1024
        const val MAX_FILE_BYTES = 128L * 1024 * 1024
        private const val BYTES_PER_MIB = 1024L * 1024L
    }
}
