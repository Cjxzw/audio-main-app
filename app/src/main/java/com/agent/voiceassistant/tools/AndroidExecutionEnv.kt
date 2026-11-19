package com.agent.voiceassistant.tools

import android.content.Context
import android.util.Base64
import com.agent.voiceassistant.cloud.NetworkTimeoutException
import com.agent.voiceassistant.workspace.WorkspaceTrashRepository
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidExecutionEnv(
    context: Context,
    private val credentialStore: CredentialProfileStore = CredentialProfileStore(context),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    data class ReadResult(
        val path: String,
        val kind: String,
        val content: String,
        val startLine: Int?,
        val endLine: Int?,
        val totalLines: Int?,
        val truncated: Boolean,
        val filterSummary: String? = null,
        val sha256: String? = null,
        val encoding: String = "utf8",
        val totalBytes: Long? = null,
        val byteOffset: Long? = null,
        val bytesRead: Int? = null,
        val nextByteOffset: Long? = null,
        val hasMore: Boolean = false,
    )

    data class WriteResult(
        val path: String,
        val bytesWritten: Int,
        val mode: String,
        val sha256: String,
        val warning: String? = null,
    )

    data class FindResult(
        val root: String,
        val query: String,
        val matches: List<String>,
        val truncated: Boolean,
    )

    data class GrepMatch(
        val path: String,
        val line: Int,
        val text: String,
    )

    data class GrepResult(
        val matches: List<GrepMatch>,
        val truncated: Boolean,
        val filesScanned: Int,
    )

    data class ScriptResult(
        val skillName: String,
        val resource: String,
        val version: String,
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    data class HttpResult(
        val status: Int,
        val contentType: String?,
        val body: String,
        val truncated: Boolean,
        val headers: Map<String, List<String>> = emptyMap(),
        val savedPath: String? = null,
        val savedBytes: Long? = null,
        val savedSha256: String? = null,
        val savedTruncated: Boolean = false,
        val savedExpiresAt: Long? = null,
        val binary: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val runtimeRoot = File(appContext.filesDir, "agent-runtime")
    private val credentialVault = CredentialVault(appContext)
    val sourceRoot = File(runtimeRoot, "source")
    val logsRoot = File(runtimeRoot, "logs")
    val workspaceRoot = File(runtimeRoot, "workspace")
    val skillsRoot = File(runtimeRoot, "skills")
    val disabledSkillsRoot = File(runtimeRoot, "skills-disabled")
    val deletedSkillsManifest = File(runtimeRoot, "skills-deleted")
    val modifiedSkillsManifest = File(runtimeRoot, "skills-user-modified")
    val systemSkillsRoot = File(runtimeRoot, "system-skills")
    val disabledSystemSkillsManifest = File(runtimeRoot, "system-skills-disabled")
    private val pathResolver = VirtualPathResolver(sourceRoot, logsRoot, workspaceRoot)
    private val zipArchives = ZipArchiveOperations(pathResolver)
    private val httpResponseStore = HttpResponseStore(workspaceRoot)
    private val workspaceTrash = WorkspaceTrashRepository(appContext)

    init {
        makeWritable(systemSkillsRoot)
        systemSkillsRoot.deleteRecursively()
        listOf(runtimeRoot, logsRoot, workspaceRoot, skillsRoot, disabledSkillsRoot, systemSkillsRoot).forEach(File::mkdirs)
        installAssetTree("source", sourceRoot, marker = fingerprintAssetTree("source"))
        installBundledSkills()
    }

    fun read(
        path: String,
        offset: Int? = null,
        limit: Int = DEFAULT_READ_LINES,
        tailLines: Int? = null,
        logLevels: List<String> = emptyList(),
        logTags: List<String> = emptyList(),
        eventPrefixes: List<String> = emptyList(),
        query: String? = null,
        encoding: String = "utf8",
        byteOffset: Long? = null,
        byteLimit: Int? = null,
    ): ReadResult {
        BinaryFileCodec.requireEncoding(encoding)
        require(encoding != "utf8" || (byteOffset == null && byteLimit == null)) { "utf8 使用行参数，不支持 byte_offset/byte_limit" }
        if (encoding != "utf8") {
            require(offset == null && tailLines == null && limit == DEFAULT_READ_LINES && logLevels.isEmpty() && logTags.isEmpty() && eventPrefixes.isEmpty() && query == null) { "二进制读取不能混用行参数或日志筛选" }
            val file = pathResolver.resolve(path, write = false)
            require(file.isFile && file.length() <= MAX_BINARY_FILE_BYTES) { "二进制读取仅支持不超过 8 MiB 的文件" }
            val start = byteOffset ?: 0L
            require(start in 0..file.length()) { "byte_offset 超出文件范围" }
            val maximum = if (encoding == "base64") 28_000 else 18_000
            val count = byteLimit ?: maximum
            require(count in 1..maximum) { "byte_limit 必须在 1..$maximum 之间" }
            val bytes = ByteArray(minOf(count.toLong(), file.length() - start).toInt())
            java.io.RandomAccessFile(file, "r").use { it.seek(start); it.readFully(bytes) }
            val next = start + bytes.size
            return ReadResult(pathResolver.normalize(path), "file", BinaryFileCodec.encode(bytes, encoding), null, null, null,
                next < file.length() || start > 0, sha256 = sha256File(file), encoding = encoding, totalBytes = file.length(),
                byteOffset = start, bytesRead = bytes.size, nextByteOffset = next, hasMore = next < file.length())
        }
        offset?.let { require(it >= 1) { "offset 必须从 1 开始" } }
        require(limit in 1..MAX_READ_LINES) { "limit 必须在 1..$MAX_READ_LINES 之间" }
        tailLines?.let { require(it in 1..MAX_READ_LINES) { "tail_lines 必须在 1..$MAX_READ_LINES 之间" } }
        require(offset == null || tailLines == null) { "offset 和 tail_lines 不能同时使用" }
        val normalizedPath = pathResolver.normalize(path)
        val file = pathResolver.resolve(path, write = false)
        val hasLogFilters = logLevels.isNotEmpty() || logTags.isNotEmpty() ||
            eventPrefixes.isNotEmpty() || !query.isNullOrBlank()
        require(!hasLogFilters || normalizedPath.startsWith("/logs/")) {
            "日志筛选参数只能用于 /logs 下的文件"
        }
        require(file.exists()) { "路径不存在：$path" }
        if (file.isDirectory) {
            return readDirectory(normalizedPath, file, offset ?: 1, limit)
        }
        require(file.length() <= MAX_READ_FILE_BYTES) { "文件过大，不能直接读取：$path" }
        if (file.length() > STREAMING_READ_THRESHOLD_BYTES) {
            require(!hasLogFilters) { "大文件暂不支持日志筛选，请先分段读取原始内容" }
            return try { readLargeTextFile(normalizedPath, file, offset, limit, tailLines) }
            catch (_: java.nio.charset.CharacterCodingException) { error("文件不是有效 UTF-8，请使用 encoding=base64 或 hex") }
        }
        val text = BinaryFileCodec.utf8(file.readBytes())
        require(!text.contains('\u0000')) { "暂不支持读取二进制文件：$path" }
        val sourceLines = text.lines()
        val lines = if (hasLogFilters) {
            LogFilterPolicy.filter(sourceLines, logLevels, logTags, eventPrefixes, query)
        } else {
            sourceLines
        }
        val effectiveTail = tailLines ?: if (offset == null && normalizedPath.startsWith("/logs/")) limit else null
        val startLine = if (effectiveTail != null) {
            (lines.size - effectiveTail).coerceAtLeast(0) + 1
        } else {
            offset ?: 1
        }
        val selected = lines.drop(startLine - 1).take(if (effectiveTail != null) effectiveTail else limit)
        val fullContent = selected.joinToString("\n")
        val content = fullContent
            .let { if (it.length > MAX_READ_OUTPUT_CHARS) it.take(MAX_READ_OUTPUT_CHARS) else it }
        val endLine = if (selected.isEmpty()) startLine - 1 else startLine + selected.size - 1
        return ReadResult(
            path = normalizedPath,
            kind = "file",
            content = content,
            startLine = startLine,
            endLine = endLine,
            totalLines = lines.size,
            truncated = startLine > 1 || endLine < lines.size || fullContent.length > content.length,
            filterSummary = if (hasLogFilters) {
                buildString {
                    append("扫描 ${sourceLines.size} 行，筛选后 ${lines.size} 行")
                    if (logLevels.isNotEmpty()) append("；级别=${logLevels.joinToString(",")}")
                    if (logTags.isNotEmpty()) append("；标签=${logTags.joinToString(",")}")
                    if (eventPrefixes.isNotEmpty()) append("；事件=${eventPrefixes.joinToString(",")}")
                    query?.takeIf(String::isNotBlank)?.let { append("；关键词=$it") }
                }
            } else {
                null
            },
            sha256 = sha256File(file),
        )
    }

    private fun readLargeTextFile(
        normalizedPath: String,
        file: File,
        offset: Int?,
        limit: Int,
        tailLines: Int?,
    ): ReadResult {
        val selected = mutableListOf<String>()
        val tail = ArrayDeque<String>()
        var totalLines = 0
        java.io.InputStreamReader(file.inputStream(), BinaryFileCodec.decoder()).buffered().useLines { lines ->
            lines.forEach { line ->
                require(!line.contains('\u0000')) { "暂不支持读取二进制文件：$normalizedPath" }
                totalLines += 1
                if (tailLines != null) {
                    tail.addLast(line)
                    if (tail.size > tailLines) tail.removeFirst()
                } else if (totalLines >= (offset ?: 1) && selected.size < limit) {
                    selected += line
                }
            }
        }
        val effectiveTail = tailLines != null
        val resultLines = if (effectiveTail) tail.toList() else selected
        val startLine = if (effectiveTail) {
            (totalLines - resultLines.size).coerceAtLeast(0) + 1
        } else {
            offset ?: 1
        }
        val fullContent = resultLines.joinToString("\n")
        val content = fullContent.take(MAX_READ_OUTPUT_CHARS)
        val endLine = if (resultLines.isEmpty()) startLine - 1 else startLine + resultLines.size - 1
        return ReadResult(
            path = normalizedPath,
            kind = "file",
            content = content,
            startLine = startLine,
            endLine = endLine,
            totalLines = totalLines,
            truncated = startLine > 1 || endLine < totalLines || fullContent.length > content.length,
            sha256 = sha256File(file),
        )
    }

    @Synchronized
    fun write(
        path: String,
        content: String,
        mode: String = "overwrite",
        startLine: Int? = null,
        endLine: Int? = null,
        expectedSha256: String? = null,
        encoding: String = "utf8",
    ): WriteResult {
        BinaryFileCodec.requireEncoding(encoding)
        require(!CredentialPolicy.anyReference.containsMatchIn(path)) { "路径不能包含凭证引用" }
        if (encoding == "base64") require(content.length <= (MAX_BINARY_FILE_BYTES + 2) / 3 * 4) { "二进制写入上限 8 MiB" }
        if (encoding == "hex") require(content.length <= MAX_BINARY_FILE_BYTES * 2) { "二进制写入上限 8 MiB" }
        val bytes = BinaryFileCodec.decode(content, encoding)
        // Encoded input must not bypass the existing literal-reference write policy.
        require(!CredentialPolicy.anyReference.containsMatchIn(bytes.toString(Charsets.UTF_8))) { "写入内容不能包含凭证引用；通过 Skill input 传入 HTTP" }
        val file = pathResolver.resolve(path, write = true)
        require(!file.isDirectory) { "写入目标不能是目录" }
        val normalizedMode = mode.lowercase()
        require(normalizedMode in setOf("create", "overwrite", "append", "patch")) { "不支持的写入模式：$mode" }
        require(encoding == "utf8" || normalizedMode != "patch") { "二进制写入不支持 patch" }
        require(normalizedMode == "patch" || (startLine == null && endLine == null)) { "行参数仅用于 patch" }
        require(normalizedMode != "patch" || file.isFile) { "patch 目标文件不存在：$path" }
        require(normalizedMode != "create" || !file.exists()) { "文件已存在：$path" }
        expectedSha256?.let { expected ->
            require(file.isFile && sha256File(file) == expected.trim().lowercase()) { "文件已变化：$path；请重新读取后再修改" }
        }
        if (encoding != "utf8") require(bytes.size.toLong() + (if (normalizedMode == "append") file.length() else 0) <= MAX_BINARY_FILE_BYTES) { "二进制写入上限 8 MiB" }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output ->
                when (normalizedMode) {
                    "append" -> {
                        if (file.exists()) {
                            if (encoding == "utf8") BinaryFileCodec.utf8(file.readBytes())
                            file.inputStream().use { it.copyTo(output) }
                        }
                        output.write(bytes)
                    }
                    "patch" -> output.write(TextPatchApplier.apply(BinaryFileCodec.utf8(file.readBytes()), content, startLine, endLine).toByteArray(Charsets.UTF_8))
                    else -> output.write(bytes)
                }
            }
            if (normalizedMode == "create") Files.move(temporary.toPath(), file.toPath())
            else Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        val lines = if (encoding == "utf8") content.count { it == '\n' } + 1 else 0
        val warning = if (lines > MAX_WRITE_LINES || bytes.size > MAX_WRITE_BYTES)
            "本次写入已成功；下次建议每段不超过 50 行和 8 KiB，不要重复本次写入。" else null
        return WriteResult(pathResolver.normalize(path), bytes.size, normalizedMode, sha256File(file), warning)
    }

    fun fileSha256(path: String, deadlineNanos: Long = Long.MAX_VALUE): String {
        val file = pathResolver.resolve(path, write = false)
        require(file.isFile && file.length() <= MAX_READ_FILE_BYTES) { "sha256 仅支持不超过 128 MiB 的文件" }
        return sha256File(file, deadlineNanos)
    }

    fun zip(paths: List<String>, outputPath: String, maxBytes: Long, overwrite: Boolean, deadlineNanos: Long) =
        zipArchives.zip(paths, outputPath, maxBytes, overwrite, deadlineNanos)

    fun unzip(path: String, outputDir: String, maxBytes: Long, maxEntries: Int, filenameCharset: String, deadlineNanos: Long) =
        zipArchives.unzip(path, outputDir, maxBytes, maxEntries, filenameCharset, deadlineNanos)

    fun compress(
        command: String, path: String?, base64: String?, outputPath: String?,
        maxBytes: Long, previewChars: Int, overwrite: Boolean, deadlineNanos: Long,
    ): kotlinx.serialization.json.JsonObject {
        require(command in setOf("gunzip", "inflate", "gzip", "deflate")) { "未知压缩命令" }
        require((path == null) != (base64 == null)) { "path 与 base64 必须二选一" }
        require(maxBytes in 1..MAX_BINARY_FILE_BYTES) { "max_bytes 必须在 1..8388608 之间" }
        require(previewChars in 0..2000) { "preview_chars 必须在 0..2000 之间" }
        val deadline = minOf(deadlineNanos, System.nanoTime() + 120_000_000_000L)
        check(System.nanoTime() < deadline) { "runtime_timeout: 执行预算已用尽" }
        val destination = outputPath?.let { pathResolver.resolve(it, write = true) }
        require(destination == null || (!destination.isDirectory && (overwrite || !destination.exists()))) { "输出文件已存在，覆盖需 overwrite=true" }
        val inlineInput = if (path == null) File.createTempFile("compress-input-", ".tmp", appContext.cacheDir) else null
        var temporary: File? = null
        try {
            val input = if (path != null) pathResolver.resolve(path, write = false) else inlineInput!!.also {
                require(base64!!.length <= 40_000) { "内联 base64 输入过大，请使用 path" }
                it.writeBytes(BinaryFileCodec.decode(base64, "base64"))
            }
            require(input.isFile && input.length() <= MAX_READ_FILE_BYTES) { "压缩输入仅支持不超过 128 MiB 的文件" }
            require(input.canonicalFile != destination?.canonicalFile) { "输入输出不能是同一文件" }
            destination?.parentFile?.mkdirs()
            val output = File.createTempFile(".hanwo-compress-", ".tmp", destination?.parentFile ?: appContext.cacheDir)
            temporary = output
            val inlineLimit = minOf(28_000L, (38_000L - previewChars * 6) * 3 / 4)
            val outputLimit = if (destination == null) minOf(maxBytes, inlineLimit) else maxBytes
            val bytes = android.os.ParcelFileDescriptor.open(input, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                android.os.ParcelFileDescriptor.open(output, android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_TRUNCATE).use { target ->
                    com.agent.voiceassistant.tools.runtime.NativeCompression.transform(source.fd, target.fd, command, outputLimit, deadline)
                }
            }
            val hash = sha256File(output, deadline)
            val inline = if (destination == null) BinaryFileCodec.encode(output.readBytes(), "base64") else null
            val preview = if (previewChars == 0) null else {
                val prefix = output.inputStream().use { stream -> ByteArray(minOf(output.length(), 8004L).toInt()).let { b -> val n = stream.read(b); if (n < 0) "" else b.copyOf(n).toString(Charsets.UTF_8).take(previewChars) } }
                prefix
            }
            val result = kotlinx.serialization.json.buildJsonObject {
                outputPath?.let { put("output_path", kotlinx.serialization.json.JsonPrimitive(pathResolver.normalize(it))) }
                put("bytes", kotlinx.serialization.json.JsonPrimitive(bytes))
                put("sha256", kotlinx.serialization.json.JsonPrimitive(hash))
                inline?.let { put("base64", kotlinx.serialization.json.JsonPrimitive(it)) }
                preview?.let { put("preview", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("has_more", kotlinx.serialization.json.JsonPrimitive(preview != null && bytes > preview.toByteArray(Charsets.UTF_8).size))
            }
            require(result.toString().length <= 40_000) { "压缩结果超出输出预算，请减少 preview_chars 或使用 output_path" }
            check(System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) { "runtime_timeout: 压缩结果发布前超时或取消" }
            if (destination != null) {
                if (overwrite) Files.move(output.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                else Files.move(output.toPath(), destination.toPath())
            }
            return result
        } finally { inlineInput?.delete(); temporary?.delete() }
    }

    fun find(
        path: String = "/workspace",
        query: String,
        maxResults: Int = DEFAULT_FIND_RESULTS,
        maxDepth: Int = DEFAULT_FIND_DEPTH,
    ): FindResult {
        val normalizedRoot = pathResolver.normalize(path)
        val root = pathResolver.resolve(normalizedRoot, write = false)
        require(root.isDirectory) { "查找根路径不是目录：$path" }
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty()) { "find 缺少 query" }
        require(normalizedQuery.length <= MAX_FIND_QUERY_CHARS) { "find query 过长" }
        require(maxResults in 1..MAX_FIND_RESULTS) { "max_results 必须在 1..$MAX_FIND_RESULTS 之间" }
        require(maxDepth in 0..MAX_FIND_DEPTH) { "max_depth 必须在 0..$MAX_FIND_DEPTH 之间" }

        val matches = mutableListOf<String>()
        val collectionLimit = maxResults + 1
        val rootCanonical = root.canonicalFile.toPath()

        fun visit(directory: File, virtualPath: String, depth: Int) {
            if (matches.size >= collectionLimit) return
            directory.listFiles().orEmpty()
                .sortedBy { it.name.lowercase() }
                .forEach { entry ->
                    if (matches.size >= collectionLimit) return@forEach
                    val canonical = runCatching { entry.canonicalFile.toPath() }.getOrNull() ?: return@forEach
                    if (!canonical.startsWith(rootCanonical)) return@forEach
                    val entryPath = "$virtualPath/${entry.name}".replace(Regex("/+"), "/")
                    if (entry.name.contains(normalizedQuery, ignoreCase = true)) {
                        matches += entryPath
                    }
                    if (entry.isDirectory && depth < maxDepth) {
                        visit(entry, entryPath, depth + 1)
                    }
                }
        }

        visit(root, normalizedRoot, 0)
        return FindResult(
            root = normalizedRoot,
            query = normalizedQuery,
            matches = matches.take(maxResults),
            truncated = matches.size > maxResults,
        )
    }

    fun grep(
        paths: List<String>,
        pattern: String,
        ignoreCase: Boolean = false,
        maxResults: Int = DEFAULT_GREP_RESULTS,
    ): GrepResult {
        require(paths.isNotEmpty()) { "grep 缺少 path 或 paths" }
        require(paths.size <= MAX_GREP_PATHS) { "paths 最多包含 $MAX_GREP_PATHS 项" }
        require(pattern.isNotEmpty()) { "grep 缺少 pattern" }
        require(pattern.length <= MAX_GREP_PATTERN_CHARS) { "grep pattern 过长" }
        require(maxResults in 1..MAX_GREP_RESULTS) { "max_results 必须在 1..$MAX_GREP_RESULTS 之间" }
        val matches = mutableListOf<GrepMatch>()
        var truncated = false
        var filesScanned = 0
        var entriesScanned = 0
        var bytesRemaining = MAX_GREP_SCAN_BYTES
        var outputChars = 0

        scan@ for (normalizedPath in paths.map(pathResolver::normalize).distinct()) {
            val root = pathResolver.resolve(normalizedPath, write = false)
            require(root.exists()) { "路径不存在：$normalizedPath" }
            val entries = root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
            for (file in entries) {
                if (++entriesScanned > MAX_GREP_ENTRIES || bytesRemaining <= 0) {
                    truncated = true
                    break@scan
                }
                if (!file.isFile || Files.isSymbolicLink(file.toPath())) continue
                val fileBudget = minOf(bytesRemaining, MAX_GREP_FILE_BYTES)
                val bytes = file.readBoundedBytes(fileBudget)
                if (bytes == null) {
                    truncated = true
                    bytesRemaining -= fileBudget
                    continue
                }
                bytesRemaining -= bytes.size
                if (0.toByte() in bytes) continue
                filesScanned += 1
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val virtualPath = if (relative.isEmpty()) normalizedPath else "$normalizedPath/$relative"
                for ((index, line) in bytes.toString(Charsets.UTF_8).lineSequence().withIndex()) {
                    if (!line.contains(pattern, ignoreCase = ignoreCase)) continue
                    val text = line.take(MAX_GREP_LINE_CHARS)
                    if (matches.size >= maxResults || outputChars + text.length + virtualPath.length > MAX_READ_OUTPUT_CHARS) {
                        truncated = true
                        break@scan
                    }
                    matches += GrepMatch(virtualPath, index + 1, text)
                    outputChars += text.length + virtualPath.length
                    if (text.length < line.length) truncated = true
                }
            }
        }
        return GrepResult(matches, truncated, filesScanned)
    }

    fun stat(path: String): FileMetadata {
        val normalizedPath = pathResolver.normalize(path)
        val file = pathResolver.resolve(normalizedPath, write = false)
        require(file.exists()) { "路径不存在：$normalizedPath" }
        return FileMetadata(normalizedPath, file.isDirectory, file.takeIf(File::isFile)?.length(), file.lastModified())
    }

    fun countText(path: String): TextCounts {
        val file = pathResolver.resolve(path, write = false)
        require(file.isFile) { "wc 只接受文件路径" }
        val bytes = file.readBoundedBytes(MAX_GREP_FILE_BYTES) ?: error("wc 只接受最多 1 MiB 的文本文件")
        require(0.toByte() !in bytes) { "wc 不支持二进制文件" }
        val text = bytes.toString(Charsets.UTF_8)
        var words = 0
        var inWord = false
        for (character in text) {
            val isWord = !character.isWhitespace()
            if (isWord && !inWord) words++
            inWord = isWord
        }
        return TextCounts(text.count { it == '\n' }, words, bytes.size)
    }

    data class FileMetadata(val path: String, val directory: Boolean, val bytes: Long?, val modifiedAt: Long)

    data class TextCounts(val lines: Int, val words: Int, val bytes: Int)

    private fun File.readBoundedBytes(maxBytes: Int): ByteArray? {
        if (length() > maxBytes) return null
        return source().buffer().use { input ->
            if (input.request(maxBytes.toLong() + 1L)) null else input.readByteArray()
        }
    }

    fun trashWorkspacePath(path: String, conversationId: String? = null): WorkspaceTrashRepository.TrashEntry =
        workspaceTrash.moveAgentPathToTrash(path, conversationId = conversationId)

    suspend fun runSkillScript(
        script: SkillRegistry.ScriptResource,
        input: String? = null,
        timeoutSeconds: Int = DEFAULT_SCRIPT_TIMEOUT_SECONDS,
    ): ScriptResult = withContext(Dispatchers.IO) {
        val runRoot = File(appContext.cacheDir, "skill-runs/${UUID.randomUUID()}").canonicalFile
        val copiedSkill = File(runRoot, "skill").canonicalFile
        val entry = File(copiedSkill, script.resourceName).canonicalFile
        try {
            require(entry.toPath().startsWith(copiedSkill.toPath()))
            script.root.copyRecursively(copiedSkill, overwrite = false)
            require(com.agent.voiceassistant.agent.runtime.SkillApprovalStore.hashTree(copiedSkill) == script.skill.version) { "Skill 在启动时发生变化，请重新读取后运行" }
            val secrets = linkedMapOf<String, String>()
            copiedSkill.walkTopDown().filter(File::isFile).forEachIndexed { index, file ->
                if (file.extension.equals("sh", ignoreCase = true)) {
                    val original = file.readText(Charsets.UTF_8).removePrefix("\uFEFF").replace("\r\n", "\n")
                    val expanded = ScriptCredentials.expand(original, "HANWO_CREDENTIAL_${index}_", ::resolveScriptCredential)
                    secrets.putAll(expanded.environment)
                    file.writeText(expanded.source, Charsets.UTF_8)
                }
            }
            require(entry.isFile) { "脚本快照不存在：${script.resourceName}" }
              val expandedInput = input?.let(::expandScriptInput)
            val pathBridge = File(appContext.applicationInfo.nativeLibraryDir, "libhanwo_paths.so")
            require(pathBridge.isFile) { "脚本路径解析库缺失，请重新安装完整 APK" }
            val launcher = File(runRoot, "launch.sh").apply {
                writeText("test -d /workspace || { echo 'HANWO_PATH_MAPPING_UNAVAILABLE' >&2; exit 125; }\nexec /system/bin/sh \"\$1\"\n")
            }
            val process = ProcessBuilder("/system/bin/sh", launcher.absolutePath, entry.absolutePath)
                .directory(copiedSkill)
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment()["PATH"] = "/system/bin:/system/xbin"
                    builder.environment()["HANWO_WORKSPACE"] = workspaceRoot.canonicalPath
                    builder.environment()["HANWO_SOURCE"] = sourceRoot.canonicalPath
                    builder.environment()["HANWO_LOGS"] = logsRoot.canonicalPath
                    builder.environment()["HANWO_SKILL_ROOT"] = copiedSkill.canonicalPath
                    builder.environment()["LD_PRELOAD"] = pathBridge.absolutePath
                    builder.environment().putAll(secrets)
                }
                .start()
            try {
                return@withContext withContext(Dispatchers.IO) {
                    coroutineScope {
                        val stopping = AtomicBoolean(false)
                        val output = async(Dispatchers.IO) { readScriptOutput(process, stopping) }
                        val writer = async(Dispatchers.IO) {
                              try {
                                  process.outputStream.bufferedWriter(Charsets.UTF_8).use { stream ->
                                      if (expandedInput != null) stream.write(expandedInput)
                                  }
                              } catch (_: java.io.IOException) {
                                  // A script may exit without consuming stdin; retain its output and exit code.
                              }
                        }
                        val completed = withTimeoutOrNull(timeoutSeconds.coerceIn(1, MAX_SCRIPT_TIMEOUT_SECONDS) * 1_000L) {
                            while (process.isAlive) delay(40)
                            writer.await()
                            true
                        } ?: false
                        if (!completed) {
                            stopping.set(true)
                            process.destroyForcibly()
                        }
                        val captured = output.await()
                        ScriptResult(
                            skillName = script.skill.name,
                            resource = script.resourceName,
                            version = script.skill.version,
                            exitCode = if (completed) process.exitValue() else null,
                            output = captured.first,
                            timedOut = !completed,
                            truncated = captured.second,
                        )
                    }
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                process.outputStream.close()
            }
        } finally {
            runRoot.deleteRecursively()
        }
    }

    private fun resolveScriptCredential(token: String): String =
        if (token.startsWith("{{cred:")) credentialVault.resolveForScript(token)
        else {
            val name = token.removePrefix("{{credential.").removeSuffix("}}").substringBeforeLast('.')
            credentialStore.expandReferences(name, token) ?: error("凭证引用无法解析：$token")
        }

    private fun expandScriptInput(input: String): String {
        fun expand(value: String) = CredentialPolicy.anyReference.replace(value) { resolveScriptCredential(it.value) }
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(input) }.getOrNull() ?: return expand(input)
        fun visit(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (value) {
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(value.mapValues { visit(it.value) })
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(value.map(::visit))
            is kotlinx.serialization.json.JsonPrimitive -> if (value.isString) kotlinx.serialization.json.JsonPrimitive(expand(value.content)) else value
        }
        return visit(parsed).toString()
    }

    private suspend fun readScriptOutput(process: Process, stopping: AtomicBoolean): Pair<String, Boolean> {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var truncated = false
        val input = process.inputStream
        try {
            while (true) {
                val available = input.available()
                if (available == 0) {
                    if (!process.isAlive || stopping.get()) break
                    delay(20)
                    continue
                }
                val count = input.read(buffer, 0, minOf(buffer.size, available))
                if (count < 0) break
                val retained = minOf(count, (MAX_PROCESS_OUTPUT_CHARS - bytes.size()).coerceAtLeast(0))
                bytes.write(buffer, 0, retained)
                if (retained < count) truncated = true
            }
        } catch (error: IOException) {
            if (!stopping.get()) throw error
        }
        return bytes.toString("UTF-8") to truncated
    }

    private fun writeTextAtomically(file: File, content: String) {
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(content, Charsets.UTF_8)
            require(temporary.renameTo(file)) { "无法原子替换文件：${file.name}" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256File(file: File, deadlineNanos: Long = Long.MAX_VALUE): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(System.nanoTime() < deadlineNanos && !Thread.currentThread().isInterrupted) { "runtime_timeout: 文件摘要超时或已取消" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    suspend fun httpRequest(
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = null,
        credentialProfile: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 5_000,
        downloadTo: String? = null,
    ): HttpResult = withContext(Dispatchers.IO) {
        require(timeoutMs in 1_000..120_000) { "timeout_ms 必须在 1000 到 120000 之间" }
        val resolvedUrl = credentialProfile
            ?.takeIf(String::isNotBlank)
            ?.let { credentialStore.resolveUrl(it, url) }
            ?: url
        credentialProfile?.takeIf(String::isNotBlank)?.let { profile -> credentialStore.validateUrl(profile, resolvedUrl) }
        require(!CredentialPolicy.anyReference.containsMatchIn(resolvedUrl)) { "凭证引用不能放在 URL 中，请放入 Header 或 body" }
        val used = linkedMapOf<String, String>()
        fun redact(text: String): String {
            val variants = linkedMapOf<String, String>()
            used.forEach { (value, token) ->
                listOf(value, kotlinx.serialization.json.JsonPrimitive(value).toString().drop(1).dropLast(1),
                    java.net.URLEncoder.encode(value, "UTF-8")).filter { it.isNotEmpty() }.forEach { variants[it] = token }
            }
            if (variants.isEmpty()) return text
            return Regex(variants.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
                .replace(text) { variants.getValue(it.value) }
        }
        fun expand(text: String): String = CredentialPolicy.anyReference.replace(text) { match ->
            val value = if (match.value.startsWith("{{cred:")) credentialVault.resolve(match.value, resolvedUrl)
                else requireNotNull(credentialStore.expandReferences(credentialProfile, match.value))
            used[value] = match.value
            value
        }
        try {
            val downloadPath = downloadTo?.trim()?.takeIf(String::isNotBlank)?.let { rawPath ->
                val normalized = pathResolver.normalize(rawPath)
                require(normalized.startsWith("/workspace/")) { "download_to 只能写入 /workspace" }
                normalized to pathResolver.resolve(normalized, write = true)
            }
            require(headers.keys.none { CredentialPolicy.anyReference.containsMatchIn(it) }) { "凭证引用不能用作 Header 名称" }
            val expandedHeaders = headers.mapValues { (_, value) -> expand(value) }
            val effectiveContentType = contentType ?: headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            val expandedBody = CredentialPolicy.expandBody(body, effectiveContentType, ::expand)
            require(resolvedUrl.startsWith("https://") || resolvedUrl.startsWith("http://")) {
                "只允许 http 或 https URL"
            }
            val normalizedMethod = method.uppercase()
            require(normalizedMethod in HTTP_METHODS) { "不支持的 HTTP 方法：$method" }
            val requestBody = when {
                normalizedMethod in setOf("POST", "PUT", "PATCH") ->
                    expandedBody.orEmpty().toRequestBody((effectiveContentType ?: "application/json; charset=utf-8").toMediaTypeOrNull())
                expandedBody != null -> expandedBody.toRequestBody(effectiveContentType?.toMediaTypeOrNull())
                else -> null
            }
            val builder = Request.Builder().url(resolvedUrl).method(normalizedMethod, requestBody)
            val savedHeaders = credentialProfile?.takeIf(String::isNotBlank)?.let(credentialStore::headers).orEmpty()
            savedHeaders.forEach { (name, value) -> used[value] = "{{credential.$credentialProfile.$name}}" }
            HttpRequestPolicy.headers(savedHeaders, expandedHeaders).forEach { (name, value) -> builder.header(name, value) }
            val request = builder.build()
            var lastTimeout: IOException? = null
            val requestClient = httpClient.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(normalizedMethod in setOf("GET", "HEAD"))
                .build()
            repeat(HttpRequestPolicy.attempts(normalizedMethod)) {
                try {
                    requestClient.newCall(request).execute().use { response ->
                        val responseBody = response.body
                        val capture = responseBody?.source()?.let {
                            httpResponseStore.capture(
                                source = it,
                                contentLength = responseBody.contentLength().takeIf { length -> length >= 0L },
                                contentType = responseBody.contentType()?.toString(),
                                downloadTarget = downloadPath?.second,
                                downloadVirtualPath = downloadPath?.first,
                            )
                        } ?: HttpResponseStore.Capture(ByteArray(0), false, true)
                        val preview = if (capture.isTextual) {
                            redact(capture.preview.toString(Charsets.UTF_8))
                        } else {
                            ""
                        }
                        val artifact = capture.artifact
                        return@withContext HttpResult(
                            status = response.code,
                            contentType = responseBody?.contentType()?.toString(),
                            body = preview,
                            truncated = capture.displayTruncated,
                            headers = response.headers.toMultimap().filterKeys {
                                !it.equals("set-cookie", true) && !it.equals("authorization", true)
                            }.mapValues { (_, values) -> values.map(::redact) },
                            savedPath = downloadPath?.first ?: artifact?.virtualPath,
                            savedBytes = artifact?.bytes,
                            savedSha256 = artifact?.sha256,
                            savedTruncated = artifact?.truncated == true,
                            savedExpiresAt = artifact?.expiresAt,
                            binary = !capture.isTextual,
                        )
                    }
                } catch (error: InterruptedIOException) {
                    lastTimeout = error
                }
            }
            throw NetworkTimeoutException("HTTP request", lastTimeout)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Never attach an unsanitized cause: logs and tool failures serialize exception details.
            val safeMessage = redact(error.message ?: error.javaClass.simpleName)
            if (error is IllegalArgumentException) throw IllegalArgumentException(safeMessage)
            if (error is NetworkTimeoutException) throw NetworkTimeoutException(safeMessage)
            throw IOException(safeMessage)
        }
    }

    fun virtualRootSummary(): String = buildString {
        appendLine("/source：随 APK 构建的只读源码快照")
        appendLine("/logs：应用轮转日志，只读")
        append("/workspace：Agent 可读写工作区。Skill 目录不属于通用虚拟文件系统，只能通过 Skill 专用工具访问。")
    }

    fun describeCredentials(query: String = "", limit: Int = 10): String = buildString {
        appendLine(credentialVault.describe(query, limit))
        credentialStore.availableProfiles().filter { query.isBlank() || it.name.contains(query, true) }
            .take(limit.coerceIn(1, 20)).forEach { profile ->
                appendLine("legacy profile=${profile.name} urls=${credentialStore.allowedUrlPrefixes(profile.name).joinToString()}")
                credentialStore.entryNames(profile.name).forEach { key -> appendLine("  {{credential.${profile.name}.$key}}") }
            }
    }

    fun credentialProfileSummary(): String =
        "有效自动/新版凭证 ${credentialVault.records().size} 条，旧版 profile ${credentialStore.availableProfiles().size} 条。需要查找时调用 describe_credentials；用户消息内已有的凭证引用可直接使用。"

    private fun readDirectory(path: String, directory: File, offset: Int, limit: Int): ReadResult {
        val entries = directory.listFiles().orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val selected = entries.drop(offset - 1).take(limit)
        val content = selected.joinToString("\n") { entry ->
            if (entry.isDirectory) {
                "[dir] ${entry.name}/"
            } else {
                "[file] ${entry.name} (${entry.length()} bytes)"
            }
        }
        val end = if (selected.isEmpty()) offset - 1 else offset + selected.size - 1
        return ReadResult(
            path = path,
            kind = "directory",
            content = content.ifBlank { "[空目录]" },
            startLine = offset,
            endLine = end,
            totalLines = entries.size,
            truncated = end < entries.size,
        )
    }

    private fun installAssetTree(assetPath: String, target: File, marker: String) {
        val markerFile = File(target, ".installed-version")
        if (markerFile.readTextOrNull() == marker) return
        makeWritable(target)
        target.deleteRecursively()
        target.mkdirs()
        copyAssetTree(assetPath, target)
        markerFile.writeText(marker)
        if (target == sourceRoot) makeReadOnly(target)
    }

    private fun installBundledSkills() {
        val markerFile = File(skillsRoot, ".bundled-version")
        val manifestFile = File(skillsRoot, ".bundled-skills")
        val marker = fingerprintAssetTree("skills")
        if (markerFile.readTextOrNull() == marker) return

        val previousNames = manifestFile.readLinesOrEmpty().filter(String::isNotBlank).toSet()
        val bundledNames = appContext.assets.list("skills").orEmpty().toSet()
        val deletedNames = deletedSkillsManifest.readLinesOrEmpty().filter(String::isNotBlank).toSet()
        val modifiedNames = modifiedSkillsManifest.readLinesOrEmpty().filter(String::isNotBlank).toSet()
        (previousNames - bundledNames).forEach { name ->
            listOf(File(skillsRoot, name), File(disabledSkillsRoot, name)).forEach { target ->
                target.apply {
                    makeWritable(this)
                    deleteRecursively()
                }
            }
        }
        bundledNames.forEach { name ->
            if (name in deletedNames) return@forEach
            if (name in modifiedNames) return@forEach
            val disabled = File(disabledSkillsRoot, name)
            val target = if (disabled.exists()) disabled else File(skillsRoot, name)
            makeWritable(target)
            target.deleteRecursively()
            copyAssetTree("skills/$name", target)
        }
        manifestFile.writeText(bundledNames.sorted().joinToString("\n"))
        markerFile.writeText(marker)
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            runCatching {
                appContext.assets.open(assetPath).use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().use(input::copyTo)
                }
            }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
    }

    private fun fingerprintAssetTree(assetPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun visit(path: String) {
            val children = appContext.assets.list(path).orEmpty().sorted()
            if (children.isEmpty()) {
                digest.update(path.toByteArray())
                runCatching {
                    appContext.assets.open(path).use { input ->
                        val buffer = ByteArray(8_192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
                return
            }
            children.forEach { child -> visit("$path/$child") }
        }
        visit(assetPath)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun makeReadOnly(file: File) {
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::makeReadOnly)
        file.setWritable(false, false)
    }

    private fun makeWritable(file: File) {
        if (!file.exists()) return
        file.setWritable(true, true)
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::makeWritable)
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
    private fun File.readLinesOrEmpty(): List<String> = runCatching { readLines() }.getOrDefault(emptyList())

    companion object {
        private const val MAX_BINARY_FILE_BYTES = 8L * 1024 * 1024
        private const val DEFAULT_READ_LINES = 200
        private const val MAX_READ_LINES = 1_000
        private const val MAX_READ_FILE_BYTES = HttpResponseStore.MAX_FILE_BYTES
        private const val STREAMING_READ_THRESHOLD_BYTES = 2L * 1024 * 1024
        private const val MAX_READ_OUTPUT_CHARS = 40_000
        private const val MAX_WRITE_BYTES = 8 * 1024
        private const val MAX_WRITE_LINES = 50
        private const val DEFAULT_FIND_RESULTS = 50
        private const val MAX_FIND_RESULTS = 200
        private const val DEFAULT_FIND_DEPTH = 8
        private const val MAX_FIND_DEPTH = 16
        private const val MAX_FIND_QUERY_CHARS = 120
        private const val DEFAULT_GREP_RESULTS = 100
        private const val MAX_GREP_RESULTS = 500
        private const val MAX_GREP_PATHS = 10
        private const val MAX_GREP_PATTERN_CHARS = 256
        private const val MAX_GREP_LINE_CHARS = 4_000
        private const val MAX_GREP_ENTRIES = 5_000
        private const val MAX_GREP_FILE_BYTES = 1_024 * 1_024
        private const val MAX_GREP_SCAN_BYTES = 8 * MAX_GREP_FILE_BYTES
        private const val DEFAULT_SCRIPT_TIMEOUT_SECONDS = 30
        private const val MAX_SCRIPT_TIMEOUT_SECONDS = 120
        private const val MAX_PROCESS_OUTPUT_CHARS = 40_000
        private val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    }
}

internal object LogFilterPolicy {
    private val header = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+([VDIWE?])/([^:]+):\\s?(.*)$")

    fun filter(
        lines: List<String>,
        levels: List<String>,
        tags: List<String>,
        eventPrefixes: List<String>,
        query: String?,
    ): List<String> {
        val acceptedLevels = levels.map { it.trim().uppercase() }.filter(String::isNotBlank).toSet()
        val acceptedTags = tags.map(::normalizeTag).filter(String::isNotBlank).toSet()
        val prefixes = eventPrefixes.map { it.trim().lowercase() }.filter(String::isNotBlank)
        val needle = query?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        return entries(lines).filter { entry ->
            (acceptedLevels.isEmpty() || entry.level in acceptedLevels) &&
                (acceptedTags.isEmpty() || normalizeTag(entry.tag) in acceptedTags) &&
                (prefixes.isEmpty() || prefixes.any { entry.message.lowercase().startsWith(it) }) &&
                (needle == null || entry.lines.any { it.lowercase().contains(needle) })
        }.flatMap(LogEntry::lines)
    }

    private fun entries(lines: List<String>): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        var current: LogEntry? = null
        lines.forEach { line ->
            val match = header.matchEntire(line)
            if (match != null) {
                current?.let(entries::add)
                current = LogEntry(
                    level = match.groupValues[1],
                    tag = match.groupValues[2],
                    message = match.groupValues[3],
                    lines = mutableListOf(line),
                )
            } else if (current != null) {
                current?.lines?.add(line)
            }
        }
        current?.let(entries::add)
        return entries
    }

    private fun normalizeTag(value: String): String = value.trim().uppercase().removePrefix("VA_")

    private data class LogEntry(
        val level: String,
        val tag: String,
        val message: String,
        val lines: MutableList<String>,
    )
}

class CredentialProfileStore(context: Context) {
    data class Profile(val name: String, val baseUrl: String?)

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun putHeaders(profile: String, headers: Map<String, String>) {
        require(profile.matches(Regex("[a-zA-Z0-9._-]{1,64}"))) { "凭据配置名称无效" }
        headers.forEach { (name, value) ->
            require(name.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "HTTP Header 名称无效" }
            require(!value.contains('\r') && !value.contains('\n')) { "HTTP Header 值不能换行" }
        }
        val plaintext = headers.entries.joinToString("\n") { (name, value) -> "$name:$value" }
        preferences.edit().putString(profile, encrypt(plaintext)).apply()
    }

    fun putEntries(profile: String, entries: Map<String, String>, allowedUrlPrefixes: List<String>, baseUrl: String? = null) {
        require(profile.matches(Regex("[a-zA-Z0-9._-]{1,64}"))) { "凭据配置名称无效" }
        require(entries.isNotEmpty()) { "凭据至少需要一个键值" }
        entries.forEach { (name, value) ->
            require(name.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "凭据键名称无效" }
            require(!value.contains('\r') && !value.contains('\n')) { "凭据值不能换行" }
        }
        val legacyHeaders = headers(profile)
        val plaintext = entries.entries.joinToString("\n") { (name, value) -> "$name=$value" }
        val editor = preferences.edit()
        if (legacyHeaders.isNotEmpty()) {
            val updatedHeaders = entries.filterKeys { key -> legacyHeaders.keys.any { it.equals(key, true) } }
            if (updatedHeaders.isEmpty()) editor.remove(profile) else editor.putString(profile,
                encrypt(updatedHeaders.entries.joinToString("\n") { (key, value) -> "$key:$value" }))
        }
        editor
            .putString(entriesKey(profile), encrypt(plaintext))
            .putString(baseUrlKey(profile), baseUrl?.trimEnd('/'))
            .putString(prefixesKey(profile), allowedUrlPrefixes.joinToString("\n") { it.trimEnd('/') + "/" })
            .apply()
    }

    fun putBasic(profile: String, username: String, password: String, baseUrl: String? = null) {
        val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        putHeaders(profile, mapOf("Authorization" to "Basic $token"))
        preferences.edit().putString(baseUrlKey(profile), baseUrl?.trimEnd('/')).apply()
    }

    fun headers(profile: String): Map<String, String> {
        val encrypted = preferences.getString(profile, null) ?: return emptyMap()
        return decrypt(encrypted).lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap()
    }

    fun entries(profile: String): Map<String, String> = preferences.getString(entriesKey(profile), null)
        ?.let { parseEntries(decrypt(it)) }
        ?: headers(profile)

    fun entryNames(profile: String): List<String> = entries(profile).keys.sorted()

    fun allowedUrlPrefixes(profile: String): List<String> = preferences
        .getString(prefixesKey(profile), null)
        ?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)?.toList()
        ?.takeIf { it.isNotEmpty() }
        ?: preferences.getString(baseUrlKey(profile), null)?.let { listOf(it.trimEnd('/') + "/") }.orEmpty()

    fun validateUrl(profile: String, url: String) {
        val target = url.toHttpUrlOrNull() ?: throw IOException("HTTP URL 无效")
        val prefixes = allowedUrlPrefixes(profile)
        require(prefixes.isNotEmpty()) { "凭据 profile $profile 未配置允许的 URL 前缀" }
        require(prefixes.any { isWithinPrefix(target, it) }) {
            "HTTP 请求已拒绝：URL 不在凭据 profile $profile 的允许范围内"
        }
    }

    fun expandReferences(profile: String?, text: String?): String? =
        HttpRequestPolicy.expand(profile, text) { name, key -> entries(name)[key] }

    fun resolveUrl(profile: String, url: String): String {
        if (url.startsWith("https://") || url.startsWith("http://")) return url
        // Profiles created before the base_url field was introduced only persisted
        // url_prefixes. The first allowlisted prefix is the safe legacy base.
        val baseUrl = preferences.getString(baseUrlKey(profile), null)
            ?.takeIf(String::isNotBlank)
            ?: allowedUrlPrefixes(profile).firstOrNull()?.trimEnd('/')
            ?: throw IOException("凭据配置 $profile 没有基础地址，必须传入完整 URL")
        return "${baseUrl.trimEnd('/')}/${url.trimStart('/')}"
    }

    fun availableProfiles(): List<Profile> = HttpRequestPolicy.profileNames(preferences.all.keys)
        .map { name -> Profile(name, preferences.getString(baseUrlKey(name), null)) }

    fun delete(profile: String) {
        preferences.edit()
            .remove(profile)
            .remove(entriesKey(profile))
            .remove(prefixesKey(profile))
            .remove(baseUrlKey(profile))
            .apply()
    }

    fun putBaseUrl(profile: String, baseUrl: String?) {
        require(profile.matches(Regex("[a-zA-Z0-9._-]{1,64}"))) { "凭据配置名称无效" }
        preferences.edit().putString(baseUrlKey(profile), baseUrl?.trimEnd('/')).apply()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext.toByteArray())
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "凭据密文损坏" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun baseUrlKey(profile: String) = "$profile$BASE_URL_SUFFIX"
    private fun entriesKey(profile: String) = "$profile$ENTRIES_SUFFIX"
    private fun prefixesKey(profile: String) = "$profile$PREFIXES_SUFFIX"

    private fun parseEntries(plaintext: String): Map<String, String> = plaintext.lineSequence()
        .mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }
        .toMap()

    private fun isWithinPrefix(target: okhttp3.HttpUrl, rawPrefix: String): Boolean {
        val prefix = rawPrefix.toHttpUrlOrNull() ?: return false
        if (target.scheme != prefix.scheme || target.host != prefix.host || target.port != prefix.port) return false
        val prefixPath = prefix.encodedPath.trimEnd('/') + "/"
        val targetPath = target.encodedPath
        return targetPath == prefixPath.trimEnd('/') || targetPath.startsWith(prefixPath)
    }

    private companion object {
        private const val PREFERENCES = "credential-profiles"
        private const val KEY_ALIAS = "main-agent-credential-profiles"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val BASE_URL_SUFFIX = ".base_url"
        private const val ENTRIES_SUFFIX = ".entries"
        private const val PREFIXES_SUFFIX = ".url_prefixes"
    }
}
