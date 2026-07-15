package com.agent.voiceassistant.cloud

import android.util.Base64
import com.agent.voiceassistant.agent.LLMConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudSpeechClient(
    private val config: LLMConfig,
) {
    enum class ThinkingMode(val wireValue: String) {
        DISABLED("disabled"),
        ENABLED("enabled"),
    }

    data class ToolDefinition(
        val name: String,
        val description: String,
        val parameters: JsonObject,
    )

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
    )

    data class LlmMessage(
        val role: String,
        val content: String? = null,
        val reasoningContent: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolCallId: String? = null,
    )

    data class ChatRequest(
        val messages: List<LlmMessage>,
        val tools: List<ToolDefinition>,
        val thinkingMode: ThinkingMode,
        val maxCompletionTokens: Int,
    )

    data class ChatCompletion(
        val message: LlmMessage,
        val finishReason: String?,
    )

    sealed interface ChatStreamEvent {
        data class ContentDelta(val text: String) : ChatStreamEvent
        data class ReasoningDelta(val text: String) : ChatStreamEvent
        data class ToolCallDelta(
            val index: Int,
            val id: String?,
            val name: String?,
            val argumentsDelta: String?,
        ) : ChatStreamEvent
        data class Finished(val reason: String?) : ChatStreamEvent
    }

    data class AudioPayload(val bytes: ByteArray, val mimeType: String?)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(wavBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val audioData = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val payload = buildJsonObject {
            put("model", "mimo-v2.5-asr")
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "input_audio")
                            putJsonObject("input_audio") {
                                put("data", audioData)
                                put("format", "wav")
                            }
                        })
                    }
                })
            }
            putJsonObject("asr_options") {
                put("language", "zh")
            }
        }

        val body = executeJson(payload)
        val element = parseJsonOrNull(body)
        val text = element?.let { extractAsrText(it) } ?: body.trim()
        Timber.i("Cloud ASR: '$text'")
        text.trim()
    }

    suspend fun streamChat(
        request: ChatRequest,
        onEvent: suspend (ChatStreamEvent) -> Unit,
    ): ChatCompletion = withContext(Dispatchers.IO) {
        val payload = buildChatPayload(request)
        val accumulator = ChatStreamAccumulator()

        newJsonRequest(payload).execute().use { response ->
            val responseBody = response.body ?: throw IOException("LLM response body is empty")
            if (!response.isSuccessful) {
                throw IOException("LLM HTTP ${response.code}: ${responseBody.string().take(500)}")
            }
            val contentType = responseBody.contentType()?.toString().orEmpty()
            if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                val text = responseBody.string()
                val element = parseJsonOrNull(text)
                    ?: throw IOException("LLM returned invalid JSON: ${text.take(200)}")
                for (event in accumulator.accept(element)) onEvent(event)
                return@withContext accumulator.complete()
            }

            val source = responseBody.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val element = parseJsonOrNull(data) ?: continue
                for (event in accumulator.accept(element)) onEvent(event)
            }
        }
        accumulator.complete()
    }

    internal fun buildChatPayload(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", config.modelName)
        put("stream", true)
        put("max_completion_tokens", request.maxCompletionTokens)
        putJsonObject("thinking") {
            put("type", request.thinkingMode.wireValue)
        }
        if (request.thinkingMode == ThinkingMode.DISABLED) {
            put("temperature", config.temperature)
        }
        putJsonArray("messages") {
            request.messages.forEach { message -> add(message.toJson()) }
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

    private fun LlmMessage.toJson(): JsonObject = buildJsonObject {
        put("role", role)
        if (content != null) {
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

    suspend fun synthesizeSpeech(text: String): AudioPayload = withContext(Dispatchers.IO) {
        val full = requestTts(text, stream = false)
        if (full.bytes.isEmpty()) error("Cloud TTS returned empty audio")
        full
    }

    suspend fun streamSynthesizeSpeech(
        text: String,
        onAudioChunk: suspend (AudioPayload) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = buildTtsPayload(text, stream = true)
        newJsonRequest(payload).execute().use { response ->
            val body = response.body ?: throw IOException("TTS response body is empty")
            if (!response.isSuccessful) {
                throw IOException("TTS HTTP ${response.code}: ${body.string().take(500)}")
            }
            val contentType = body.contentType()?.toString().orEmpty()
            if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                return@withContext false
            }

            var chunks = 0
            val source = body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val parsed = parseJsonOrNull(data)
                val audio = if (parsed != null) {
                    extractAudioPayload(parsed)
                } else {
                    decodeAudioString(data, "data")
                }
                if (audio?.bytes?.isNotEmpty() == true) {
                    chunks++
                    onAudioChunk(audio)
                }
            }
            chunks > 0
        }
    }

    fun shutdown() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun requestTts(text: String, stream: Boolean): AudioPayload {
        val payload = buildTtsPayload(text, stream)

        newJsonRequest(payload).execute().use { response ->
            val body = response.body ?: throw IOException("TTS response body is empty")
            if (!response.isSuccessful) {
                throw IOException("TTS HTTP ${response.code}: ${body.string().take(500)}")
            }
            val contentType = body.contentType()?.toString()
            if (contentType?.contains("text/event-stream", ignoreCase = true) == true) {
                return readTtsEventStream(body.source().readUtf8(), contentType)
            }

            val bytes = body.bytes()
            if (looksLikeJson(bytes, contentType)) {
                val textBody = bytes.toString(Charsets.UTF_8)
                val audio = parseJsonOrNull(textBody)?.let { extractAudioPayload(it) }
                if (audio != null) return audio
                Timber.w("TTS JSON did not contain audio payload: ${textBody.take(500)}")
                return AudioPayload(ByteArray(0), contentType)
            }
            return AudioPayload(bytes, contentType)
        }
    }

    private fun buildTtsPayload(text: String, stream: Boolean): JsonObject = buildJsonObject {
        put("model", "mimo-v2.5-tts")
        if (stream) put("stream", true)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                put("content", "请用自然、温和的中文口语语气朗读。")
            })
            add(buildJsonObject {
                put("role", "assistant")
                put("content", text)
            })
        }
        putJsonObject("audio") {}
    }

    private fun executeJson(payload: JsonObject): String {
        newJsonRequest(payload).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(500)}")
            }
            return body
        }
    }

    private fun newJsonRequest(payload: JsonObject): okhttp3.Call {
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .addHeader("api-key", config.apiKey)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request)
    }

    private fun readTtsEventStream(sseText: String, contentType: String?): AudioPayload {
        val output = ByteArrayOutputStream()
        var mimeType = contentType
        sseText.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .takeWhile { it != "[DONE]" }
            .forEach { data ->
                val parsed = parseJsonOrNull(data)
                val payload = if (parsed != null) {
                    extractAudioPayload(parsed)
                } else {
                    decodeAudioString(data, "data")
                }
                if (payload != null) {
                    output.write(payload.bytes)
                    mimeType = payload.mimeType ?: mimeType
                }
            }
        return AudioPayload(output.toByteArray(), mimeType)
    }

    private fun extractAsrText(element: JsonElement): String? {
        extractAssistantDelta(element)?.let { return it }
        return findStringByKeys(element, setOf("transcript", "text", "content"))
    }

    private fun extractAssistantDelta(element: JsonElement?): String? {
        if (element !is JsonObject) return null
        val choices = element["choices"] as? JsonArray ?: return findStringByKeys(element, setOf("content", "text"))
        for (choice in choices) {
            val obj = choice as? JsonObject ?: continue
            val delta = obj["delta"] as? JsonObject
            val message = obj["message"] as? JsonObject
            contentToText(delta?.get("content"))?.let { return it }
            contentToText(message?.get("content"))?.let { return it }
        }
        return null
    }

    private fun contentToText(element: JsonElement?): String? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.mapNotNull { part ->
                when (part) {
                    is JsonPrimitive -> part.contentOrNull
                    is JsonObject -> (part["text"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            }.joinToString("").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun extractAudioPayload(element: JsonElement): AudioPayload? {
        return findAudioPayload(element, parentKey = null)
    }

    private fun findAudioPayload(element: JsonElement, parentKey: String?): AudioPayload? {
        when (element) {
            is JsonObject -> {
                val preferredKeys = listOf("audio", "data", "url", "b64_json", "base64", "content")
                for (key in preferredKeys) {
                    element[key]?.let { findAudioPayload(it, key)?.let { payload -> return payload } }
                }
                for ((key, value) in element) {
                    findAudioPayload(value, key)?.let { return it }
                }
            }
            is JsonArray -> {
                for (value in element) {
                    findAudioPayload(value, parentKey)?.let { return it }
                }
            }
            is JsonPrimitive -> {
                val value = element.contentOrNull ?: return null
                decodeAudioString(value, parentKey)?.let { return it }
            }
            JsonNull -> Unit
        }
        return null
    }

    private fun decodeAudioString(value: String, key: String?): AudioPayload? {
        val text = value.trim()
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return downloadAudio(text)
        }
        if (text.startsWith("data:audio", ignoreCase = true)) {
            val comma = text.indexOf(',')
            if (comma > 0) {
                val mime = text.substringAfter("data:").substringBefore(";")
                val bytes = Base64.decode(text.substring(comma + 1), Base64.DEFAULT)
                return AudioPayload(bytes, mime)
            }
        }
        val keySuggestsAudio = key?.contains("audio", ignoreCase = true) == true ||
            key?.contains("data", ignoreCase = true) == true ||
            key?.contains("base64", ignoreCase = true) == true ||
            key?.contains("b64", ignoreCase = true) == true
        if (!keySuggestsAudio || text.length < 128) return null
        if (!text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it.isWhitespace() }) {
            return null
        }
        return runCatching {
            val bytes = Base64.decode(text, Base64.DEFAULT)
            if (bytes.isNotEmpty()) AudioPayload(bytes, sniffMime(bytes)) else null
        }.getOrNull()
    }

    private fun downloadAudio(url: String): AudioPayload? {
        val request = Request.Builder()
            .url(url)
            .addHeader("api-key", config.apiKey)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            return AudioPayload(body.bytes(), body.contentType()?.toString())
        }
    }

    private fun findStringByKeys(element: JsonElement, keys: Set<String>): String? {
        when (element) {
            is JsonObject -> {
                for (key in keys) {
                    val value = element[key]
                    if (value is JsonPrimitive) {
                        val text = value.contentOrNull?.trim()
                        if (!text.isNullOrEmpty()) return text
                    }
                }
                for ((_, value) in element) {
                    findStringByKeys(value, keys)?.let { return it }
                }
            }
            is JsonArray -> {
                for (value in element) {
                    findStringByKeys(value, keys)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun parseJsonOrNull(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private fun looksLikeJson(bytes: ByteArray, contentType: String?): Boolean {
        if (contentType?.contains("json", ignoreCase = true) == true) return true
        val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() } ?: return false
        return first == '{'.code.toByte() || first == '['.code.toByte()
    }

    private fun sniffMime(bytes: ByteArray): String? = when {
        bytes.startsWith("RIFF") -> "audio/wav"
        bytes.startsWith("ID3") -> "audio/mpeg"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
        bytes.startsWith("OggS") -> "audio/ogg"
        bytes.startsWith("fLaC") -> "audio/flac"
        else -> null
    }

    private fun ByteArray.startsWith(prefix: String): Boolean {
        val bytes = prefix.toByteArray(Charsets.US_ASCII)
        if (size < bytes.size) return false
        for (i in bytes.indices) {
            if (this[i] != bytes[i]) return false
        }
        return true
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal class ChatStreamAccumulator {
    private data class PendingToolCall(
        var id: String = "",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )

    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val toolCalls = sortedMapOf<Int, PendingToolCall>()
    private var finishReason: String? = null

    fun accept(element: JsonElement): List<CloudSpeechClient.ChatStreamEvent> {
        if (element !is JsonObject) return emptyList()
        val events = mutableListOf<CloudSpeechClient.ChatStreamEvent>()
        val choices = element["choices"] as? JsonArray ?: return events
        for (choiceElement in choices) {
            val choice = choiceElement as? JsonObject ?: continue
            val payload = (choice["delta"] as? JsonObject)
                ?: (choice["message"] as? JsonObject)
                ?: continue

            payload.textValue("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { delta ->
                reasoning.append(delta)
                events += CloudSpeechClient.ChatStreamEvent.ReasoningDelta(delta)
            }
            payload.contentText()?.takeIf { it.isNotEmpty() }?.let { delta ->
                content.append(delta)
                events += CloudSpeechClient.ChatStreamEvent.ContentDelta(delta)
            }

            val callDeltas = payload["tool_calls"] as? JsonArray
            callDeltas?.forEachIndexed { fallbackIndex, callElement ->
                val call = callElement as? JsonObject ?: return@forEachIndexed
                val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: fallbackIndex
                val pending = toolCalls.getOrPut(index) { PendingToolCall() }
                val id = call.textValue("id")
                if (!id.isNullOrBlank()) pending.id = id
                val function = call["function"] as? JsonObject
                val nameDelta = function?.textValue("name")
                val argumentsDelta = function?.textValue("arguments")
                if (!nameDelta.isNullOrEmpty()) pending.name.append(nameDelta)
                if (!argumentsDelta.isNullOrEmpty()) pending.arguments.append(argumentsDelta)
                events += CloudSpeechClient.ChatStreamEvent.ToolCallDelta(
                    index = index,
                    id = id,
                    name = nameDelta,
                    argumentsDelta = argumentsDelta,
                )
            }

            val reason = choice.textValue("finish_reason")
            if (reason != null) {
                finishReason = reason
                events += CloudSpeechClient.ChatStreamEvent.Finished(reason)
            }
        }
        return events
    }

    fun complete(): CloudSpeechClient.ChatCompletion {
        val completedCalls = toolCalls.map { (index, pending) ->
            val id = pending.id.trim()
            val name = pending.name.toString().trim()
            if (id.isEmpty() || name.isEmpty()) {
                throw IOException("Incomplete tool call at index $index")
            }
            CloudSpeechClient.ToolCall(
                id = id,
                name = name,
                arguments = pending.arguments.toString().ifBlank { "{}" },
            )
        }
        return CloudSpeechClient.ChatCompletion(
            message = CloudSpeechClient.LlmMessage(
                role = "assistant",
                content = content.toString(),
                reasoningContent = reasoning.toString().takeIf { it.isNotEmpty() },
                toolCalls = completedCalls,
            ),
            finishReason = finishReason,
        )
    }

    private fun JsonObject.textValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.contentText(): String? {
        return when (val value = this["content"]) {
            is JsonPrimitive -> value.contentOrNull
            is JsonArray -> value.mapNotNull { part ->
                when (part) {
                    is JsonPrimitive -> part.contentOrNull
                    is JsonObject -> (part["text"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            }.joinToString("")
            else -> null
        }
    }
}
