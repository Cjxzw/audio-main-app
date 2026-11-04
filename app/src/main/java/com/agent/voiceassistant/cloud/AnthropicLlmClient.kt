package com.agent.voiceassistant.cloud

import com.agent.voiceassistant.agent.LLMConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Anthropic Messages API adapter. Requests are deliberately non-streaming for now. */
class AnthropicLlmClient(
    private val config: LLMConfig,
) : LlmClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun streamChat(
        request: CloudSpeechClient.ChatRequest,
        onEvent: suspend (CloudSpeechClient.ChatStreamEvent) -> Unit,
    ): CloudSpeechClient.ChatCompletion = withContext(Dispatchers.IO) {
        val call = client.newCall(newRequest(buildPayload(request, stream = false)))
            .also { it.timeout().timeout(config.timeoutSeconds.coerceAtMost(120), TimeUnit.SECONDS) }
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw LlmHttpException(response.code, body)
                val root = json.parseToJsonElement(body).jsonObject
                val message = parseMessage(root)
                message.content?.takeIf { it.isNotBlank() }?.let {
                    onEvent(CloudSpeechClient.ChatStreamEvent.ContentDelta(it))
                }
                message.toolCalls.forEachIndexed { index, callItem ->
                    onEvent(
                        CloudSpeechClient.ChatStreamEvent.ToolCallDelta(
                            index = index,
                            id = callItem.id,
                            name = callItem.name,
                            argumentsDelta = callItem.arguments,
                        ),
                    )
                }
                val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull
                onEvent(CloudSpeechClient.ChatStreamEvent.Finished(stopReason))
                val usage = root["usage"]?.jsonObject
                return@withContext CloudSpeechClient.ChatCompletion(
                    message = message,
                    finishReason = stopReason,
                    modelId = root["model"]?.jsonPrimitive?.contentOrNull ?: config.modelName,
                    usage = CloudSpeechClient.ChatUsage(
                        promptTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull,
                        completionTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull,
                        totalTokens = null,
                    ),
                    streamDiagnostics = CloudSpeechClient.StreamDiagnostics(
                        protocolObserved = true,
                        receivedFinishEvent = true,
                        reachedEof = true,
                        receivedDoneMarker = true,
                    ),
                )
            }
        } finally {
            cancellation?.dispose()
        }
    }

    override suspend fun testConnection(): String = testConnectionDetails().message

    override suspend fun testConnectionDetails(): LlmConnectionTest = withContext(Dispatchers.IO) {
        val request = CloudSpeechClient.ChatRequest(
            messages = listOf(CloudSpeechClient.LlmMessage("user", "这是一个连通性测试，收到请回复“已连接”")),
            tools = emptyList(),
            thinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
            maxCompletionTokens = 16,
            transportAttemptLimit = 1,
        )
        val call = client.newCall(newRequest(buildPayload(request, stream = false)))
            .also { it.timeout().timeout(10, TimeUnit.SECONDS) }
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw LlmHttpException(response.code, body)
                val root = json.parseToJsonElement(body).jsonObject
                val text = root["content"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString("")?.trim().orEmpty()
                require(text.isNotBlank()) { "连通性测试返回空正文" }
                LlmConnectionTest(text, detectContextWindow(config.modelName))
            }
        } finally {
            cancellation?.dispose()
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun newRequest(payload: JsonObject): Request = Request.Builder()
        .url("${config.baseUrl.trimEnd('/')}/messages")
        .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
        .addHeader("x-api-key", config.apiKey)
        .addHeader("anthropic-version", "2023-06-01")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private fun buildPayload(request: CloudSpeechClient.ChatRequest, stream: Boolean): JsonObject {
        val system = request.messages.filter { it.role == "system" }
            .mapNotNull { it.content?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        return buildJsonObject {
            put("model", config.modelName)
            put("max_tokens", request.maxCompletionTokens ?: 4096)
            put("stream", stream)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                request.messages.filter { it.role != "system" }.forEach { message ->
                    putMessage(this, message)
                }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.parameters)
                        })
                    }
                }
            }
        }
    }

    private fun putMessage(target: kotlinx.serialization.json.JsonArrayBuilder, message: CloudSpeechClient.LlmMessage) {
        val role = if (message.role == "tool") "user" else message.role
        target.add(buildJsonObject {
            put("role", role)
            when {
                message.role == "tool" -> putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", message.toolCallId.orEmpty())
                        put("content", message.content.orEmpty())
                    })
                }
                message.toolCalls.isNotEmpty() -> putJsonArray("content") {
                    message.content?.takeIf(String::isNotBlank)?.let { text ->
                        add(buildJsonObject { put("type", "text"); put("text", text) })
                    }
                    message.toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", call.id)
                            put("name", call.name)
                            put("input", runCatching { Json.parseToJsonElement(call.arguments) }.getOrDefault(JsonObject(emptyMap())))
                        })
                    }
                }
                message.imageInputs.isNotEmpty() -> put("content", buildJsonArray {
                    message.content?.takeIf(String::isNotBlank)?.let { text ->
                        add(buildJsonObject { put("type", "text"); put("text", text) })
                    }
                    message.imageInputs.forEach { image ->
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", image.mimeType)
                                put("data", image.base64Data)
                            }
                        })
                    }
                })
                else -> put("content", message.content.orEmpty())
            }
        })
    }

    private fun parseMessage(root: JsonObject): CloudSpeechClient.LlmMessage {
        val blocks = root["content"] as? JsonArray ?: JsonArray(emptyList())
        val text = blocks.mapNotNull { block ->
            val item = block as? JsonObject ?: return@mapNotNull null
            if (item["type"]?.jsonPrimitive?.contentOrNull == "text") item["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString("").ifBlank { null }
        val calls = blocks.mapIndexedNotNull { index, block ->
            val item = block as? JsonObject ?: return@mapIndexedNotNull null
            if (item["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@mapIndexedNotNull null
            CloudSpeechClient.ToolCall(
                id = item["id"]?.jsonPrimitive?.contentOrNull ?: "anthropic-tool-$index",
                name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                arguments = item["input"]?.toString() ?: "{}",
            )
        }
        return CloudSpeechClient.LlmMessage("assistant", text, toolCalls = calls)
    }

    private fun detectContextWindow(model: String): Long? = when {
        model.contains("claude", ignoreCase = true) -> 200_000L
        else -> null
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
