package com.agent.voiceassistant.cloud

import com.agent.voiceassistant.agent.LLMConfig
import com.agent.voiceassistant.agent.LlmProviderMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

interface LlmClient {
    suspend fun streamChat(
        request: CloudSpeechClient.ChatRequest,
        onEvent: suspend (CloudSpeechClient.ChatStreamEvent) -> Unit,
    ): CloudSpeechClient.ChatCompletion

    suspend fun testConnection(): String

    fun close() = Unit
}

internal class LlmHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IOException("LLM HTTP $statusCode: ${responseBody.take(500)}")

class OpenAiCompatibleLlmClient(
    private val config: LLMConfig,
) : LlmClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    override suspend fun streamChat(
        request: CloudSpeechClient.ChatRequest,
        onEvent: suspend (CloudSpeechClient.ChatStreamEvent) -> Unit,
    ): CloudSpeechClient.ChatCompletion {
        var firstTimeout: FirstEventTimeoutException? = null
        val attemptLimit = request.transportAttemptLimit.coerceIn(1, MAX_NETWORK_ATTEMPTS)
        repeat(attemptLimit) { attempt ->
            try {
                return streamChatOnce(request, onEvent)
            } catch (error: FirstEventTimeoutException) {
                firstTimeout = error
                if (attempt + 1 < attemptLimit) {
                    Timber.w("LLM first event timeout; retrying chat request")
                }
            }
        }
        throw firstTimeout ?: IOException("LLM request failed before receiving an event")
    }

    override suspend fun testConnection(): String {
        return withContext(Dispatchers.IO) {
            val call = newModelsCall().also {
                it.timeout().timeout(config.timeoutSeconds, TimeUnit.SECONDS)
            }
            val cancellation = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw LlmHttpException(response.code, body)
                    }
                    summarizeModelsResponse(body)
                }
            } finally {
                cancellation?.dispose()
            }
        }
    }

    internal fun summarizeModelsResponse(body: String): String {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) }
            .getOrElse { throw IOException("模型列表响应不是有效 JSON", it) }
        val models = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray
            else -> null
        } ?: throw IOException("模型列表响应缺少 data 数组")
        val modelIds = models.mapNotNull { model ->
            ((model as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        }
        if (modelIds.isEmpty()) return "模型列表可用，当前没有可用模型"
        return if (config.modelName in modelIds) {
            "模型列表可用，已找到 ${config.modelName}"
        } else {
            "模型列表可用，共 ${modelIds.size} 个模型"
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    internal fun buildChatPayload(request: CloudSpeechClient.ChatRequest): JsonObject = buildJsonObject {
        put("model", config.modelName)
        put("stream", true)
        val supportsNativeThinking = config.providerMode == LlmProviderMode.MIMO ||
            config.modelName.startsWith("deepseek", ignoreCase = true)
        if (config.providerMode == LlmProviderMode.MIMO) {
            put("max_completion_tokens", request.maxCompletionTokens)
        } else {
            put("max_tokens", request.maxCompletionTokens)
        }
        if (supportsNativeThinking) {
            putJsonObject("thinking") {
                put("type", request.thinkingMode.wireValue)
            }
        }
        if (request.responseFormat == CloudSpeechClient.ResponseFormat.JSON_OBJECT) {
            putJsonObject("response_format") { put("type", "json_object") }
        }
        if (request.thinkingMode == CloudSpeechClient.ThinkingMode.DISABLED) {
            put("temperature", config.temperature)
        }
        putJsonArray("messages") {
            request.messages.forEach { message ->
                message.toolCalls.forEach(ToolCallSafety::requireValid)
                add(message.toJson())
            }
            if (!supportsNativeThinking &&
                request.thinkingMode == CloudSpeechClient.ThinkingMode.ENABLED
            ) {
                add(
                    CloudSpeechClient.LlmMessage(
                        role = "system",
                        content = "请在内部进行更充分的分析后再回答，不要输出思考过程。",
                    ).toJson(),
                )
            }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { definition ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", definition.name)
                            put("description", definition.description)
                            put("parameters", definition.parameters)
                        }
                    })
                }
            }
            put("tool_choice", "auto")
        }
    }

    private suspend fun streamChatOnce(
        request: CloudSpeechClient.ChatRequest,
        onEvent: suspend (CloudSpeechClient.ChatStreamEvent) -> Unit,
    ): CloudSpeechClient.ChatCompletion = coroutineScope {
        val accumulator = ChatStreamAccumulator()
        var receivedDoneMarker = false
        var reachedEof = false
        var malformedEventCount = 0
        var streamingProtocol = false
        val call = newJsonCall(buildChatPayload(request)).also {
            // The read timeout remains an idle-stream limit. A continuously streaming reply
            // gets a longer bounded budget so it is not cancelled at the old 60-second mark.
            it.timeout().timeout(STREAM_HARD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val receivedEvent = AtomicBoolean(false)
        val streamStartedAtMs = monotonicNowMs()
        val lastProgressAtMs = AtomicLong(streamStartedAtMs)
        val firstEventTimedOut = AtomicBoolean(false)
        val streamIdleTimedOut = AtomicBoolean(false)
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        fun withDiagnostics(
            completion: CloudSpeechClient.ChatCompletion,
            interruptionReason: String? = null,
        ): CloudSpeechClient.ChatCompletion = completion.copy(
            modelId = config.modelName,
            streamDiagnostics = completion.streamDiagnostics.copy(
                protocolObserved = streamingProtocol,
                receivedDoneMarker = receivedDoneMarker,
                reachedEof = reachedEof,
                malformedEventCount = completion.streamDiagnostics.malformedEventCount + malformedEventCount,
                interruptionReason = interruptionReason ?: completion.streamDiagnostics.interruptionReason,
            ),
        )
        val watchdog = launch(Dispatchers.IO) {
            while (true) {
                delay(STREAM_WATCHDOG_POLL_MS)
                val idleMs = monotonicNowMs() - lastProgressAtMs.get()
                val timeoutMs = if (receivedEvent.get()) STREAM_IDLE_TIMEOUT_MS else firstEventTimeoutMs(request)
                if (idleMs >= timeoutMs) {
                    if (receivedEvent.get()) streamIdleTimedOut.set(true)
                    else firstEventTimedOut.set(true)
                    Timber.w(
                        "LLM stream watchdog requestId=${request.requestId} " +
                            "phase=${if (receivedEvent.get()) "idle" else "first_event"} " +
                            "idleMs=$idleMs timeoutMs=$timeoutMs",
                    )
                    call.cancel()
                    return@launch
                }
            }
        }

        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val body = response.body ?: throw IOException("LLM response body is empty")
                    if (!response.isSuccessful) {
                        throw LlmHttpException(response.code, body.string())
                    }
                    val contentType = body.contentType()?.toString().orEmpty()
                    if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                        val element = kotlinx.serialization.json.Json.parseToJsonElement(body.string())
                        val events = accumulator.accept(element)
                        if (events.isNotEmpty()) {
                            receivedEvent.set(true)
                            lastProgressAtMs.set(monotonicNowMs())
                        }
                        events.forEach { onEvent(it) }
                        return@withContext
                    }
                    streamingProtocol = true
                    val source = body.source()
                    while (true) {
                        val line = source.readUtf8Line()
                        if (line == null) {
                            reachedEof = true
                            break
                        }
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") {
                            receivedDoneMarker = true
                            break
                        }
                        val element = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(data)
                        }.getOrNull()
                        if (element == null) {
                            malformedEventCount += 1
                            continue
                        }
                        val events = accumulator.accept(element)
                        if (events.isNotEmpty()) {
                            receivedEvent.set(true)
                            lastProgressAtMs.set(monotonicNowMs())
                        }
                        events.forEach { onEvent(it) }
                    }
                }
            }
            val completion = accumulator.complete()
            withDiagnostics(completion)
        } catch (error: IOException) {
            if (firstEventTimedOut.get() && !receivedEvent.get()) {
                throw FirstEventTimeoutException(error)
            }
            if (receivedEvent.get() && streamingProtocol) {
                val interruption = if (streamIdleTimedOut.get()) {
                    "idle_watchdog"
                } else {
                    "transport_${error.javaClass.simpleName}"
                }
                Timber.w(
                    "LLM stream interrupted after output requestId=${request.requestId} " +
                        "reason=$interruption; returning partial completion for continuation",
                )
                return@coroutineScope withDiagnostics(accumulator.complete(), interruption)
            }
            if (streamIdleTimedOut.get()) {
                throw StreamIdleTimeoutException(error)
            }
            throw error
        } finally {
            watchdog.cancelAndJoin()
            cancellation?.dispose()
        }
    }

    private fun newJsonCall(payload: JsonObject): okhttp3.Call {
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .addHeader("api-key", config.apiKey)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request)
    }

    private fun newModelsCall(): okhttp3.Call {
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/models")
            .addHeader("Accept", "application/json")
            .addHeader("api-key", config.apiKey)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        return client.newCall(request)
    }

    private fun CloudSpeechClient.LlmMessage.toJson(): JsonObject = buildJsonObject {
        put("role", role)
        if (imageInputs.isNotEmpty()) {
            putJsonArray("content") {
                content?.takeIf { it.isNotBlank() }?.let { text ->
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                }
                imageInputs.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:${image.mimeType};base64,${image.base64Data}")
                        }
                    })
                }
            }
        } else if (content != null) {
            put("content", content)
        } else if (role == "assistant" && toolCalls.isNotEmpty()) {
            put("content", "")
        }
        reasoningContent?.let { put("reasoning_content", it) }
        toolCallId?.let { put("tool_call_id", it) }
        if (toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", call.name)
                            put("arguments", call.arguments)
                        }
                    })
                }
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val FIRST_EVENT_TIMEOUT_MS = 15_000L
        const val STREAM_HARD_TIMEOUT_SECONDS = 120L
        const val STREAM_IDLE_TIMEOUT_MS = 45_000L
        const val STREAM_WATCHDOG_POLL_MS = 250L
        const val MAX_NETWORK_ATTEMPTS = 2

        fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

        fun firstEventTimeoutMs(request: CloudSpeechClient.ChatRequest): Long {
            val promptChars = request.messages.sumOf { message ->
                message.content.orEmpty().length + message.reasoningContent.orEmpty().length
            }
            return when {
                promptChars >= 50_000 -> 45_000L
                promptChars >= 20_000 -> 30_000L
                else -> FIRST_EVENT_TIMEOUT_MS
            }
        }
    }
}
