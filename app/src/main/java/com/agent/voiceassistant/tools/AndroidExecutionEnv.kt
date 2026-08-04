package com.agent.voiceassistant.tools

import android.content.Context
import android.util.Base64
import com.agent.voiceassistant.cloud.NetworkTimeoutException
import com.agent.voiceassistant.workspace.WorkspaceTrashRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
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
        .build(),
) {
    data class ReadResult(
        val path: String,
        val kind: String,
        val content: String,
        val startLine: Int,
        val endLine: Int,
        val totalLines: Int,
        val truncated: Boolean,
        val filterSummary: String? = null,
        val sha256: String? = null,
    )

    data class WriteResult(
        val path: String,
        val bytesWritten: Int,
        val mode: String,
        val sha256: String,
    )

    data class ExecResult(
        val command: String,
        val cwd: String,
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
    )

    private val appContext = context.applicationContext
    private val runtimeRoot = File(appContext.filesDir, "agent-runtime")
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
    private val workspaceTrash = WorkspaceTrashRepository(appContext)

    init {
        listOf(runtimeRoot, logsRoot, workspaceRoot, skillsRoot, disabledSkillsRoot, systemSkillsRoot).forEach(File::mkdirs)
        installAssetTree("source", sourceRoot, marker = fingerprintAssetTree("source"))
        installBundledSkills()
        installAssetTree("system-skills", systemSkillsRoot, marker = fingerprintAssetTree("system-skills"))
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
    ): ReadResult {
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
        val text = file.readText(Charsets.UTF_8)
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
            sha256 = sha256(text.toByteArray()),
        )
    }

    fun write(
        path: String,
        content: String,
        mode: String = "overwrite",
        startLine: Int? = null,
        endLine: Int? = null,
        expectedSha256: String? = null,
    ): WriteResult {
        require(content.toByteArray().size <= MAX_WRITE_BYTES) { "单次写入不能超过 $MAX_WRITE_BYTES 字节" }
        val file = pathResolver.resolve(path, write = true)
        file.parentFile?.mkdirs()
        val normalizedMode = mode.lowercase()
        require(normalizedMode != "patch" || file.exists()) { "patch 目标文件不存在：$path" }
        val current = if (file.exists()) file.readText(Charsets.UTF_8) else ""
        expectedSha256?.trim()?.takeIf { it.isNotEmpty() }?.let { expected ->
            require(sha256(current.toByteArray()) == expected.lowercase()) {
                "文件已变化：$path；请重新读取后再修改"
            }
        }
        val updated = when (normalizedMode) {
            "overwrite" -> content
            "append" -> current + content
            "create" -> {
                require(file.createNewFile()) { "文件已存在：$path" }
                content
            }
            "patch" -> TextPatchApplier.apply(current, content, startLine, endLine)
            else -> error("不支持的写入模式：$mode")
        }
        if (normalizedMode != "create") writeTextAtomically(file, updated)
        else file.writeText(updated, Charsets.UTF_8)
        return WriteResult(
            path = pathResolver.normalize(path),
            bytesWritten = content.toByteArray().size,
            mode = normalizedMode,
            sha256 = sha256(updated.toByteArray()),
        )
    }

    fun trashWorkspacePath(path: String, conversationId: String? = null): WorkspaceTrashRepository.TrashEntry =
        workspaceTrash.moveAgentPathToTrash(path, conversationId = conversationId)

    suspend fun exec(
        argv: List<String>,
        timeoutSeconds: Int = DEFAULT_EXEC_TIMEOUT_SECONDS,
        cwd: String = "/workspace",
    ): ExecResult {
        require(argv.isNotEmpty()) { "argv 不能为空" }
        require(argv.size <= MAX_EXEC_ARGV_ITEMS) { "argv 项目过多" }
        require(argv.none { it.length > MAX_EXEC_ARG_CHARS }) { "argv 参数过长" }
        require(argv.none { argument -> argument.replace('\\', '/').split('/').contains("..") }) {
            "exec 参数不能通过 .. 离开已授权虚拟目录"
        }
        val displayCommand = argv.joinToString(" ") { argument ->
            if (argument.any(Char::isWhitespace)) "\"${argument.replace("\"", "\\\"")}" else argument
        }
        require(!WorkspaceDeletePolicy.attemptsDirectDeletion(argv)) {
            "exec 不允许直接删除文件；请使用 workspace_delete，以便将 Agent 删除的内容移入回收站"
        }
        require(!WorkspaceDeletePolicy.attemptsShellExecution(argv)) {
            "exec 不允许启动 shell 或多命令解释器；请使用 argv 调用单个程序"
        }
        val timeout = timeoutSeconds.coerceIn(1, MAX_EXEC_TIMEOUT_SECONDS)
        val normalizedCwd = pathResolver.normalize(cwd)
        val physicalCwd = pathResolver.resolve(normalizedCwd, write = false)
        require(physicalCwd.isDirectory) { "cwd 不是目录：$cwd" }
        val resolvedArgv = argv.map(::resolveExecArgument)
        return withContext(Dispatchers.IO) {
            val process = ProcessBuilder(resolvedArgv)
                .directory(physicalCwd)
                .redirectErrorStream(true)
                .start()
            try {
                coroutineScope {
                    val output = async(Dispatchers.IO) { readProcessOutput(process) }
                    val completed = withTimeoutOrNull(timeout * 1_000L) {
                        while (process.isAlive) delay(40)
                        true
                    } ?: false
                    if (!completed) process.destroyForcibly()
                    val captured = output.await()
                    ExecResult(
                        command = displayCommand,
                        cwd = normalizedCwd,
                        exitCode = if (completed) process.exitValue() else null,
                        output = captured.first,
                        timedOut = !completed,
                        truncated = captured.second,
                    )
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
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

    private fun resolveExecArgument(argument: String): String {
        if (!argument.startsWith('/')) return argument
        return pathResolver.resolve(argument, write = false).absolutePath
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    suspend fun httpRequest(
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = null,
        credentialProfile: String? = null,
    ): HttpResult = withContext(Dispatchers.IO) {
        val resolvedUrl = credentialProfile
            ?.takeIf(String::isNotBlank)
            ?.let { credentialStore.resolveUrl(it, url) }
            ?: url
        require(resolvedUrl.startsWith("https://") || resolvedUrl.startsWith("http://")) {
            "只允许 http 或 https URL"
        }
        val normalizedMethod = method.uppercase()
        require(normalizedMethod in HTTP_METHODS) { "不支持的 HTTP 方法：$method" }
        val requestBody = when {
            normalizedMethod in setOf("POST", "PUT", "PATCH") ->
                body.orEmpty().toRequestBody((contentType ?: "application/json; charset=utf-8").toMediaTypeOrNull())
            body != null -> body.toRequestBody(contentType?.toMediaTypeOrNull())
            else -> null
        }
        val builder = Request.Builder().url(resolvedUrl).method(normalizedMethod, requestBody)
        credentialProfile?.takeIf(String::isNotBlank)?.let { profile ->
            credentialStore.headers(profile).forEach(builder::addHeader)
        }
        val request = builder.build()
        var lastTimeout: IOException? = null
        repeat(MAX_HTTP_ATTEMPTS) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body
                    val bounded = responseBody?.source()?.let {
                        BoundedSourceReader.read(it, MAX_HTTP_BODY_BYTES.toLong())
                    } ?: BoundedSourceReader.Result(ByteArray(0), truncated = false)
                    return@withContext HttpResult(
                        status = response.code,
                        contentType = responseBody?.contentType()?.toString(),
                        body = bounded.bytes.toString(Charsets.UTF_8),
                        truncated = bounded.truncated,
                    )
                }
            } catch (error: InterruptedIOException) {
                lastTimeout = error
            }
        }
        throw NetworkTimeoutException("HTTP request", lastTimeout)
    }

    fun virtualRootSummary(): String = buildString {
        appendLine("/source：随 APK 构建的只读源码快照")
        appendLine("/logs：应用轮转日志，只读")
        append("/workspace：Agent 可读写工作区。Skill 目录不属于通用虚拟文件系统，只能通过 Skill 专用工具访问。")
    }

    fun credentialProfileSummary(): String {
        val profiles = credentialStore.availableProfiles()
        if (profiles.isEmpty()) return "当前没有可用凭据 profile。"
        return profiles.joinToString("\n") { profile ->
            val base = profile.baseUrl?.let { "，基础地址 $it" }.orEmpty()
            "- ${profile.name}$base"
        }
    }

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

    private fun readProcessOutput(process: Process): Pair<String, Boolean> {
        val output = StringBuilder()
        var truncated = false
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (output.length + line.length + 1 <= MAX_EXEC_OUTPUT_CHARS) {
                    output.appendLine(line)
                } else {
                    truncated = true
                }
            }
        }
        return output.toString().trimEnd() to truncated
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
    private fun File.readLinesOrEmpty(): List<String> = runCatching { readLines() }.getOrDefault(emptyList())

    companion object {
        private const val DEFAULT_READ_LINES = 200
        private const val MAX_READ_LINES = 1_000
        private const val MAX_READ_FILE_BYTES = 2L * 1024 * 1024
        private const val MAX_READ_OUTPUT_CHARS = 40_000
        private const val MAX_WRITE_BYTES = 8 * 1024
        private const val DEFAULT_EXEC_TIMEOUT_SECONDS = 30
        private const val MAX_EXEC_TIMEOUT_SECONDS = 120
        private const val MAX_EXEC_ARGV_ITEMS = 64
        private const val MAX_EXEC_ARG_CHARS = 4_000
        private const val MAX_EXEC_OUTPUT_CHARS = 40_000
        private const val MAX_HTTP_BODY_BYTES = 512 * 1024
        private const val MAX_HTTP_ATTEMPTS = 2
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

    fun putBasic(profile: String, username: String, password: String, baseUrl: String? = null) {
        val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        putHeaders(profile, mapOf("Authorization" to "Basic $token"))
        preferences.edit().putString(baseUrlKey(profile), baseUrl?.trimEnd('/')).apply()
    }

    fun headers(profile: String): Map<String, String> {
        val encrypted = preferences.getString(profile, null)
            ?: throw IOException("凭据配置不存在：$profile")
        return decrypt(encrypted).lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap()
    }

    fun resolveUrl(profile: String, url: String): String {
        if (url.startsWith("https://") || url.startsWith("http://")) return url
        val baseUrl = preferences.getString(baseUrlKey(profile), null)
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("凭据配置 $profile 没有基础地址，必须传入完整 URL")
        return "${baseUrl.trimEnd('/')}/${url.trimStart('/')}"
    }

    fun availableProfiles(): List<Profile> = preferences.all.keys
        .filterNot { it.endsWith(BASE_URL_SUFFIX) }
        .sorted()
        .map { name -> Profile(name, preferences.getString(baseUrlKey(name), null)) }

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

    private companion object {
        private const val PREFERENCES = "credential-profiles"
        private const val KEY_ALIAS = "main-agent-credential-profiles"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val BASE_URL_SUFFIX = ".base_url"
    }
}
