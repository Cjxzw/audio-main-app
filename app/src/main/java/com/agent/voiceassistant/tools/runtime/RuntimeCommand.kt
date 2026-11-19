package com.agent.voiceassistant.tools.runtime

import com.agent.voiceassistant.tools.AndroidExecutionEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

data class RuntimeCommandResult(
    val value: JsonElement,
)

/** The capability table shared by the one-shot run tool and QuickJS Skills. */
class RuntimeCommandRegistry(
    private val executionEnv: AndroidExecutionEnv,
    private val allowedCommands: Set<String>? = null,
    private val conversationIdProvider: () -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val effectiveCommands: Set<String> get() = allowedCommands ?: COMMANDS

    fun execute(command: String, args: JsonElement, deadlineNanos: Long = Long.MAX_VALUE): RuntimeCommandResult {
        require(command in COMMANDS) { "不支持的运行时命令：$command" }
        require(allowedCommands == null || command in allowedCommands) { "Skill 未获准使用运行时命令：$command" }
        val objectArgs = args as? JsonObject ?: error("运行时命令参数必须是 JSON 对象")
        return RuntimeCommandResult(
            when (command) {
                "read" -> read(objectArgs)
                "list" -> list(objectArgs)
                "find" -> find(objectArgs)
                "grep" -> grep(objectArgs)
                "stat" -> stat(objectArgs)
                "wc" -> wc(objectArgs)
                "write" -> write(objectArgs)
                "http_request" -> httpRequest(objectArgs)
                "delete" -> delete(objectArgs)
                "describe_credentials" -> describeCredentials(objectArgs)
                "sha256" -> sha256(objectArgs, deadlineNanos)
                "gunzip", "inflate", "gzip", "deflate" -> compress(command, objectArgs, deadlineNanos)
                "zip", "unzip" -> zipArchive(command, objectArgs, deadlineNanos)
                else -> error("未实现的运行时命令：$command")
            },
        )
    }

    fun executeJson(command: String, argsJson: String): String =
        execute(command, json.parseToJsonElement(argsJson)).value.toString()

    private fun read(args: JsonObject): JsonElement {
        val paths = buildList {
            args.string("path")?.let(::add)
            addAll(args.stringList("paths"))
        }.distinct()
        require(paths.isNotEmpty()) { "read 缺少 path 或 paths" }
        require(paths.size <= MAX_BATCH_READ_ITEMS) { "paths 最多包含 $MAX_BATCH_READ_ITEMS 项" }
        if (paths.size == 1) return readOne(args, paths.single())
        require((args.stringOrNull("encoding") ?: "utf8") == "utf8") { "二进制读取每次只接受一个 path" }

        return buildJsonObject {
            put("items", buildJsonArray {
                paths.forEach { path ->
                    runCatching {
                        val item = readOne(args, path) as JsonObject
                        val content = item["content"]!!.jsonPrimitive.content
                        JsonObject(item + mapOf("content" to JsonPrimitive(content.take(1000)), "truncated" to JsonPrimitive(content.length > 1000 || item["truncated"]!!.jsonPrimitive.content == "true")))
                    }.fold(
                        onSuccess = { result ->
                            add(buildJsonObject {
                                put("ok", true)
                                put("requested_path", path)
                                put("result", result)
                            })
                        },
                        onFailure = { error ->
                            add(buildJsonObject {
                                put("ok", false)
                                put("requested_path", path)
                                put("error", error.message ?: error.javaClass.simpleName)
                            })
                        },
                    )
                }
            })
        }
    }

    private fun readOne(args: JsonObject, path: String): JsonElement {
        require((args.stringOrNull("encoding") ?: "utf8") == "utf8" || listOf("offset", "limit", "tail_lines", "log_levels", "log_tags", "event_prefixes", "query").none { it in args }) { "二进制读取不能混用行参数或日志筛选" }
        val result = executionEnv.read(
            path = path,
            offset = args.int("offset"),
            limit = args.int("limit") ?: 200,
            tailLines = args.int("tail_lines"),
            logLevels = args.stringList("log_levels"),
            logTags = args.stringList("log_tags"),
            eventPrefixes = args.stringList("event_prefixes"),
            query = args.string("query"),
            encoding = args.stringOrNull("encoding") ?: "utf8",
            byteOffset = args.long("byte_offset"),
            byteLimit = args.int("byte_limit"),
        )
        require(result.kind != "directory") { "read 只能读取文件；列出目录请使用 list" }
        return readResult(result)
    }

    private fun list(args: JsonObject): JsonElement {
        val result = executionEnv.read(
            path = args.string("path") ?: "/workspace",
            offset = args.int("offset"),
            limit = args.int("limit") ?: 200,
        )
        require(result.kind == "directory") { "list 只能列出目录" }
        return readResult(result)
    }

    private fun find(args: JsonObject): JsonElement {
        val result = executionEnv.find(
            path = args.string("path") ?: "/workspace",
            query = args.string("query") ?: error("find 缺少 query"),
            maxResults = args.int("max_results") ?: 50,
            maxDepth = args.int("max_depth") ?: 8,
        )
        return buildJsonObject {
            put("root", result.root)
            put("query", result.query)
            put("match_mode", "literal_substring")
            put("matches", JsonArray(result.matches.map(::JsonPrimitive)))
            put("truncated", result.truncated)
        }
    }

    private fun grep(args: JsonObject): JsonElement {
        require(args.boolean("regex") != true) { "grep 只支持字面匹配；正则处理请在 QuickJS Skill 中完成" }
        val paths = buildList {
            args.string("path")?.let(::add)
            addAll(args.stringList("paths"))
        }.distinct()
        val result = executionEnv.grep(
            paths = paths,
            pattern = args.stringOrNull("pattern") ?: error("grep 缺少 pattern"),
            ignoreCase = args.boolean("ignore_case") ?: false,
            maxResults = args.int("max_results") ?: 100,
        )
        return buildJsonObject {
            put("matches", buildJsonArray {
                result.matches.forEach { match ->
                    add(buildJsonObject {
                        put("path", match.path)
                        put("line", match.line)
                        put("text", match.text)
                    })
                }
            })
            put("truncated", result.truncated)
            put("files_scanned", result.filesScanned)
        }
    }

    private fun stat(args: JsonObject): JsonElement {
        val result = executionEnv.stat(args.string("path") ?: error("stat 缺少 path"))
        return buildJsonObject {
            put("path", result.path)
            put("kind", if (result.directory) "directory" else "file")
            result.bytes?.let { put("bytes", it) }
            put("modified_at_ms", result.modifiedAt)
        }
    }

    private fun wc(args: JsonObject): JsonElement {
        val result = executionEnv.countText(args.string("path") ?: error("wc 缺少 path"))
        return buildJsonObject {
            put("lines", result.lines)
            put("words", result.words)
            put("bytes", result.bytes)
        }
    }

    private fun readResult(result: AndroidExecutionEnv.ReadResult): JsonElement {
        val value = buildJsonObject {
            put("path", result.path)
            put("kind", result.kind)
            put("content", result.content)
            put("start_line", result.startLine)
            put("end_line", result.endLine)
            put("total_lines", result.totalLines)
            put("truncated", result.truncated)
            put("encoding", result.encoding)
            result.totalBytes?.let { put("total_bytes", it) }
            result.byteOffset?.let { put("byte_offset", it) }
            result.bytesRead?.let { put("bytes_read", it) }
            result.nextByteOffset?.let { put("next_byte_offset", it) }
            if (result.byteOffset != null) put("has_more", result.hasMore)
            result.filterSummary?.let { put("filter_summary", it) }
            result.sha256?.let { put("sha256", it) }
        }
        if (value.toString().length <= 40_000) return value
        require(result.encoding == "utf8") { "二进制结果超出输出预算，请减小 byte_limit" }
        // JSON escaping also consumes the model-output budget. Keep metadata and mark truncation.
        var low = 0
        var high = result.content.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val candidate = JsonObject(value + mapOf("content" to JsonPrimitive(result.content.take(mid)), "truncated" to JsonPrimitive(true)))
            if (candidate.toString().length <= 40_000) low = mid else high = mid - 1
        }
        return JsonObject(value + mapOf("content" to JsonPrimitive(result.content.take(low)), "truncated" to JsonPrimitive(true)))
    }

    private fun write(args: JsonObject): JsonElement {
        val result = executionEnv.write(
            path = args.string("path") ?: error("write 缺少 path"),
            content = args.stringOrNull("content") ?: error("write 缺少 content"),
            mode = args.string("mode") ?: "overwrite",
            startLine = args.int("start_line"),
            endLine = args.int("end_line"),
            expectedSha256 = args.string("expected_sha256"),
            encoding = args.stringOrNull("encoding") ?: "utf8",
        )
        return buildJsonObject {
            put("path", result.path)
            put("bytes_written", result.bytesWritten)
            put("mode", result.mode)
            put("sha256", result.sha256)
            result.warning?.let { put("warning", it) }
        }
    }

    private fun httpRequest(args: JsonObject): JsonElement {
        val headers = (args["headers"] as? JsonObject).orEmpty().mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull ?: error("HTTP Header 必须是字符串")
        }
        val result = runBlocking {
            executionEnv.httpRequest(
                method = args.string("method") ?: "GET",
                url = args.string("url") ?: error("http_request 缺少 url"),
                body = args.stringOrNull("body"),
                contentType = args.string("content_type"),
                credentialProfile = args.string("credential_profile"),
                headers = headers,
                timeoutMs = args.int("timeout_ms") ?: 5_000,
                downloadTo = args.string("download_to"),
            )
        }
        return buildJsonObject {
            put("status", result.status)
            result.contentType?.let { put("content_type", it) }
            put("headers", buildJsonObject {
                result.headers.forEach { (name, values) ->
                    put(name, buildJsonArray { values.forEach { value -> add(JsonPrimitive(value)) } })
                }
            })
            put("body", result.body)
            put("truncated", result.truncated)
            put("binary", result.binary)
            result.savedPath?.let { put("saved_path", it) }
            result.savedBytes?.let { put("saved_bytes", it) }
            result.savedSha256?.let { put("saved_sha256", it) }
            put("saved_truncated", result.savedTruncated)
            result.savedExpiresAt?.let { put("saved_expires_at", it) }
        }
    }

    private fun delete(args: JsonObject): JsonElement {
        val entry = executionEnv.trashWorkspacePath(
            args.string("path") ?: error("delete 缺少 path"),
            conversationId = conversationIdProvider(),
        )
        return buildJsonObject {
            put("path", entry.originalPath)
            put("name", entry.name)
            put("deleted", true)
        }
    }

    private fun describeCredentials(args: JsonObject): JsonElement =
        buildJsonObject { put("summary", executionEnv.describeCredentials(args.string("query").orEmpty(), args.int("limit") ?: 10)) }

    private fun sha256(args: JsonObject, deadlineNanos: Long): JsonElement {
        require(("path" in args) != ("value" in args)) { "sha256 的 path 与 value 必须二选一" }
        val path = if ("path" in args) args.string("path") ?: error("sha256 path 不能为空") else null
        val value = if ("value" in args) args.stringOrNull("value") ?: error("sha256 value 必须是字符串") else null
        val digest = if (path != null) executionEnv.fileSha256(path, deadlineNanos)
        else MessageDigest.getInstance("SHA-256").digest(value!!.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return buildJsonObject { put("sha256", digest) }
    }

    private fun compress(command: String, args: JsonObject, deadlineNanos: Long): JsonElement {
        require(("path" in args) != ("base64" in args)) { "path 与 base64 必须二选一" }
        require("output_path" !in args || args.string("output_path") != null) { "output_path 不能为空" }
        return executionEnv.compress(command, args.string("path"), args.stringOrNull("base64"), args.string("output_path"),
            args.long("max_bytes") ?: 8L * 1024 * 1024, args.int("preview_chars") ?: 0,
            args.boolean("overwrite") ?: false, deadlineNanos)
    }

    private fun zipArchive(command: String, args: JsonObject, deadlineNanos: Long): JsonElement {
        val maxBytes = args.long("max_bytes") ?: 8L * 1024 * 1024
        return if (command == "zip") {
            require(("path" in args) != ("paths" in args)) { "zip 的 path 与 paths 必须二选一" }
            val inputs = if ("path" in args) listOf(args.string("path") ?: error("zip path 不能为空")) else {
                val values = args["paths"] as? JsonArray ?: error("zip paths 必须是字符串数组")
                values.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) ?: error("zip paths 必须是非空字符串数组") }
            }
            require("overwrite" !in args || args.boolean("overwrite") != null) { "overwrite 必须是布尔值" }
            executionEnv.zip(inputs, args.string("output_path") ?: error("zip 缺少 output_path"), maxBytes, args.boolean("overwrite") ?: false, deadlineNanos)
        } else {
            require("overwrite" !in args && "paths" !in args && "output_path" !in args) { "unzip 使用 path 和新的 output_dir，不支持 overwrite 或 output_path" }
            executionEnv.unzip(args.string("path") ?: error("unzip 缺少 path"), args.string("output_dir") ?: error("unzip 缺少 output_dir"),
                maxBytes, args.int("max_entries") ?: 1000, args.string("filename_charset") ?: "UTF-8", deadlineNanos)
        }
    }

    companion object {
        val RUN_COMMANDS = setOf(
            "list",
            "find",
            "grep",
            "stat",
            "wc",
            "sha256",
        )

        val COMMANDS = RUN_COMMANDS + setOf("read", "write", "delete", "http_request", "describe_credentials", "gunzip", "inflate", "gzip", "deflate", "zip", "unzip")

        fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

        fun JsonObject.stringOrNull(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.int(key: String): Int? =
            this[key]?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: error("$key 必须是整数") }

        fun JsonObject.long(key: String): Long? =
            this[key]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: error("$key 必须是整数") }

        fun JsonObject.boolean(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

        fun JsonObject.stringList(key: String): List<String> {
            val value = this[key] ?: return emptyList()
            return when (value) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                is JsonPrimitive -> value.contentOrNull
                    ?.split(",", "，")
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    .orEmpty()
                else -> error("$key 必须是字符串数组")
            }
        }

        private const val MAX_BATCH_READ_ITEMS = 10
    }
}
