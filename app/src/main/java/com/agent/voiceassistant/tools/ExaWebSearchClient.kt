package com.agent.voiceassistant.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fast public web search used by MiMo Code through the Exa MCP endpoint. */
class ExaWebSearchClient {
    data class Source(
        val title: String?,
        val url: String,
        val summary: String?,
        val siteName: String? = null,
        val publishTime: String? = null,
    )

    data class SearchResult(
        val answer: String,
        val sources: List<Source>,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): SearchResult = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "搜索关键词不能为空" }
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "web_search_exa")
                putJsonObject("arguments") {
                    put("query", query.trim())
                    put("type", "fast")
                    put("numResults", limit.coerceIn(1, MAX_RESULTS))
                    put("livecrawl", "fallback")
                }
            }
        }
        val startedAt = System.nanoTime()
        val request = Request.Builder()
            .url(EXA_URL)
            .addHeader("Accept", "application/json, text/event-stream")
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Exa Web Search HTTP ${response.code}: ${body.take(300)}")
            }
            val result = parseResponse(body, limit)
            Timber.i(
                "Exa web search completed query=${query.take(80)} " +
                    "elapsedMs=${elapsedMs(startedAt)} sources=${result.sources.size}",
            )
            result
        }
    }

    internal fun parseResponse(body: String, limit: Int = DEFAULT_LIMIT): SearchResult {
        val text = body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .mapNotNull { line ->
                val data = line.removePrefix("data:").trim()
                runCatching {
                    val root = JSON.parseToJsonElement(data) as? JsonObject ?: return@runCatching null
                    val result = root["result"] as? JsonObject ?: return@runCatching null
                    val content = result["content"] as? JsonArray ?: return@runCatching null
                    content.firstNotNullOfOrNull { item ->
                        val entry = item as? JsonObject ?: return@firstNotNullOfOrNull null
                        (entry["text"] as? JsonPrimitive)?.contentOrNull
                    }
                }.getOrNull()
            }
            .firstOrNull { it.isNotBlank() }
            ?: throw IOException("Exa Web Search 未返回结果")

        val sources = text.split(Regex("\\n\\s*---\\s*\\n"))
            .mapNotNull(::parseSourceBlock)
            .distinctBy { it.url }
            .take(limit.coerceIn(1, MAX_RESULTS))
        if (sources.isEmpty()) throw IOException("Exa Web Search 返回了无法解析的结果")
        return SearchResult(answer = "", sources = sources)
    }

    private fun parseSourceBlock(block: String): Source? {
        val url = field(block, "URL")
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        val highlights = block.substringAfter("Highlights:", "")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_SUMMARY_CHARS)
            .ifBlank { null }
        return Source(
            title = field(block, "Title"),
            url = url,
            summary = highlights,
            publishTime = field(block, "Published"),
        )
    }

    private fun field(block: String, name: String): String? =
        block.lineSequence()
            .firstOrNull { it.startsWith("$name:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {
        private const val EXA_URL = "https://mcp.exa.ai/mcp"
        private const val CONNECT_TIMEOUT_SECONDS = 3L
        private const val SEARCH_TIMEOUT_SECONDS = 8L
        private const val DEFAULT_LIMIT = 5
        private const val MAX_RESULTS = 5
        private const val MAX_SUMMARY_CHARS = 600
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
