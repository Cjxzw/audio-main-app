package com.agent.voiceassistant.tools

import com.agent.voiceassistant.agent.LLMConfig
import com.agent.voiceassistant.cloud.NetworkTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import timber.log.Timber

/** Xiaomi MiMo Web Search, using the same chat-completions plugin protocol as MiMo Code. */
class MimoWebSearchClient(
    private val config: LLMConfig,
) {
    data class Source(
        val title: String?,
        val url: String,
        val summary: String?,
        val siteName: String?,
        val publishTime: String?,
    )

    data class SearchResult(
        val answer: String,
        val sources: List<Source>,
        val toolUsage: Int?,
        val pageUsage: Int?,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): SearchResult = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "搜索关键词不能为空" }
        check(config.apiKey.isNotBlank()) { "未配置 MiMo API Key" }

        val payload = buildJsonObject {
            put("model", SEARCH_MODEL)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", query.trim())
                })
            }
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "web_search")
                    put("max_keyword", MAX_KEYWORDS)
                    put("force_search", true)
                    put("limit", limit.coerceIn(1, MAX_RESULTS))
                })
            }
            put("max_completion_tokens", MAX_COMPLETION_TOKENS)
            put("temperature", 1.0)
            put("top_p", 0.95)
            put("stream", true)
            putJsonObject("thinking") { put("type", "disabled") }
        }

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .addHeader("Accept", "text/event-stream")
            .addHeader("api-key", config.apiKey)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val startedAt = System.nanoTime()
        try {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 409) {
                        throw IOException("MiMo Web Search 免费额度已用完或插件未开通")
                    }
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        throw IOException("MiMo Web Search HTTP ${response.code}: ${body.take(300)}")
                    }
                    val source = response.body?.source()
                        ?: throw IOException("MiMo Web Search 返回了空响应")
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val sources = parseStreamEvent(line) ?: continue
                        Timber.i(
                            "MiMo web search first_results query=${query.take(80)} " +
                                "elapsedMs=${elapsedMs(startedAt)} sources=${sources.size}",
                        )
                        return@withContext SearchResult(
                            answer = "",
                            sources = sources,
                            toolUsage = null,
                            pageUsage = null,
                        )
                    }
                    throw IOException("MiMo Web Search 未返回搜索结果")
                }
            } catch (error: InterruptedIOException) {
                Timber.w(
                    "MiMo web search timeout query=${query.take(80)} " +
                        "elapsedMs=${elapsedMs(startedAt)} timeoutSeconds=$SEARCH_TIMEOUT_SECONDS",
                )
                throw NetworkTimeoutException("web search", error, SEARCH_TIMEOUT_SECONDS)
            }
        } catch (error: NetworkTimeoutException) {
            throw error
        } catch (error: IOException) {
            Timber.w(error, "MiMo web search request failed query=${query.take(80)}")
            throw error
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    internal fun parseResponse(body: String): SearchResult {
        val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }
            .getOrNull()
            ?: throw IOException("MiMo Web Search 返回了无效 JSON")
        val message = (root["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.asObject()
            ?.get("message")
            .asObject()
        val answer = message?.get("content").stringValue().orEmpty().trim()
        val sources = (message?.get("annotations") as? JsonArray)
            .orEmpty()
            .mapNotNull(::parseSource)
            .distinctBy { it.url }
            .take(MAX_RESULTS)
        val webUsage = root["usage"].asObject()?.get("web_search_usage").asObject()

        if (answer.isBlank() && sources.isEmpty()) {
            throw IOException("MiMo Web Search 未返回搜索结果")
        }
        return SearchResult(
            answer = answer,
            sources = sources,
            toolUsage = webUsage?.get("tool_usage").intValue(),
            pageUsage = webUsage?.get("page_usage").intValue(),
        )
    }

    internal fun parseStreamEvent(line: String): List<Source>? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isBlank() || data == "[DONE]") return null
        val root = runCatching { JSON.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        val choice = (root["choices"] as? JsonArray)?.firstOrNull().asObject()
        val annotations = choice?.get("delta").asObject()?.get("annotations") as? JsonArray
            ?: choice?.get("message").asObject()?.get("annotations") as? JsonArray
            ?: return null
        return annotations
            .mapNotNull(::parseSource)
            .distinctBy { it.url }
            .take(MAX_RESULTS)
            .takeIf { it.isNotEmpty() }
    }

    private fun parseSource(element: JsonElement): Source? {
        val source = element.asObject() ?: return null
        val url = source["url"].stringValue()?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        return Source(
            title = source["title"].stringValue()?.trim()?.take(MAX_FIELD_CHARS),
            url = url,
            summary = source["summary"].stringValue()?.trim()?.take(MAX_SUMMARY_CHARS),
            siteName = source["site_name"].stringValue()?.trim()?.take(MAX_FIELD_CHARS),
            publishTime = source["publish_time"].stringValue()?.trim()?.take(MAX_FIELD_CHARS),
        )
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.intValue(): Int? = (this as? JsonPrimitive)?.intOrNull

    private companion object {
        const val SEARCH_MODEL = "mimo-v2.5"
        const val DEFAULT_LIMIT = 5
        const val MAX_RESULTS = 5
        const val MAX_KEYWORDS = 3
        const val MAX_COMPLETION_TOKENS = 256
        const val MAX_FIELD_CHARS = 160
        const val MAX_SUMMARY_CHARS = 600
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val SEARCH_TIMEOUT_SECONDS = 15L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
