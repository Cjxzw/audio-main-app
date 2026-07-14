package com.agent.voiceassistant.tools

import com.agent.voiceassistant.agent.LLMConfig
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
import java.util.concurrent.TimeUnit

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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
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
            put("stream", false)
            putJsonObject("thinking") { put("type", "disabled") }
        }

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .addHeader("api-key", config.apiKey)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 409) {
                throw IOException("MiMo Web Search 免费额度已用完或插件未开通")
            }
            if (!response.isSuccessful) {
                throw IOException("MiMo Web Search HTTP ${response.code}: ${body.take(300)}")
            }
            parseResponse(body)
        }
    }

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
        const val MAX_COMPLETION_TOKENS = 384
        const val MAX_FIELD_CHARS = 160
        const val MAX_SUMMARY_CHARS = 600
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
