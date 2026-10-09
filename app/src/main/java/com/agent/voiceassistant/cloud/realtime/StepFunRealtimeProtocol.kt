package com.agent.voiceassistant.cloud.realtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.settings.StepFunRealtimeConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** Wire adapter for the StepFun OpenAI-compatible Realtime WebSocket protocol. */
object StepFunRealtimeProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    sealed interface Event {
        data object SessionCreated : Event
        data object SessionUpdated : Event
        data object UserSpeechStarted : Event
        data object UserSpeechStopped : Event
        data class UserTranscriptDelta(val itemId: String?, val text: String) : Event
        data class UserTranscriptCandidate(val itemId: String?, val text: String) : Event
        data class UserTranscriptDone(val itemId: String?, val text: String) : Event
        data class TextDelta(val itemId: String?, val text: String) : Event
        data class AudioTranscriptDelta(val itemId: String?, val text: String) : Event
        data class ThinkingDelta(val itemId: String?, val text: String) : Event
        data class AudioDelta(val itemId: String?, val base64Audio: String) : Event
        data object AudioDone : Event
        data class FunctionArgumentsDone(
            val callId: String,
            val name: String,
            val arguments: String,
        ) : Event
        data class ResponseDone(val responseId: String?, val status: String?) : Event
        data object ResponseCreated : Event
        data class Error(val eventId: String?, val message: String) : Event
        data class Unknown(val type: String) : Event
    }

    fun websocketUrl(modelId: String): String = "${com.agent.voiceassistant.settings.RealtimePipelineRepository.ENDPOINT}?model=${modelId.trim()}"

    fun sessionUpdate(
        config: StepFunRealtimeConfig,
        instructions: String,
        tools: List<CloudSpeechClient.ToolDefinition>,
    ): JsonObject = clientEvent("session.update") {
        putJsonObject("session") {
            putJsonArray("modalities") {
                add(JsonPrimitive("text"))
                add(JsonPrimitive("audio"))
            }
            put("instructions", instructions)
            put("voice", config.voice)
            put("input_audio_format", "pcm16")
            put("output_audio_format", "pcm16")
            if (config.serverVadEnabled) {
                putJsonObject("turn_detection") {
                    put("type", "server_vad")
                    put("silence_duration_ms", config.vadSilenceDurationMs)
                }
            } else {
                put("turn_detection", kotlinx.serialization.json.JsonNull)
            }
            putJsonArray("tools") {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        }
                    })
                }
            }
            put("tool_choice", "auto")
        }
    }

    fun appendAudio(base64Pcm: String): JsonObject = clientEvent("input_audio_buffer.append") {
        put("audio", base64Pcm)
    }

    fun commitAudio(): JsonObject = clientEvent("input_audio_buffer.commit")

    fun createResponse(): JsonObject = clientEvent("response.create")

    fun cancelResponse(): JsonObject = clientEvent("response.cancel")

    fun createTextMessage(text: String): JsonObject = clientEvent("conversation.item.create") {
        putJsonObject("item") {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            }
        }
    }

    fun createContextMessage(text: String): JsonObject = clientEvent("conversation.item.create") {
        putJsonObject("item") {
            put("type", "message")
            // Step Realtime accepts only user/assistant conversation items. The
            // startup snapshot is context supplied by the app, so encode it as
            // an assistant-side context message instead of the rejected system role.
            put("role", "assistant")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            }
        }
    }

    fun functionOutput(callId: String, output: String): JsonObject = clientEvent("conversation.item.create") {
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        }
    }

    fun truncateAssistantAudio(itemId: String, audioEndMs: Long): JsonObject = clientEvent("conversation.item.truncate") {
        put("item_id", itemId)
        put("content_index", 0)
        put("audio_end_ms", audioEndMs.coerceAtLeast(0))
    }

    fun parse(raw: String): Event {
        val payload = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return Event.Error(null, "Realtime 服务返回了无法解析的事件")
        val type = payload.string("type") ?: return Event.Error(payload.string("event_id"), "Realtime 事件缺少 type")
        val itemId = payload.string("item_id")
        return when (type) {
            "session.created" -> Event.SessionCreated
            "session.updated" -> Event.SessionUpdated
            "input_audio_buffer.speech_started" -> Event.UserSpeechStarted
            "input_audio_buffer.speech_stopped" -> Event.UserSpeechStopped
            "conversation.item.created" -> {
                val item = payload["item"]?.jsonObject
                val transcript = item
                    ?.get("content")
                    ?.let { content -> content as? JsonArray }
                    ?.flatMap { content ->
                        val part = content as? JsonObject
                        part
                            ?.takeIf { it.string("type") in setOf("input_audio", "audio") }
                            ?.string("transcript")
                            ?.let(::listOf)
                            .orEmpty()
                    }
                    ?.joinToString("")
                    .orEmpty()
                if (item?.string("role") == "user" && transcript.isNotBlank()) {
                    Event.UserTranscriptCandidate(item.string("id"), transcript)
                } else {
                    Event.Unknown(type)
                }
            }
            "conversation.item.input_audio_transcription.delta" -> Event.UserTranscriptDelta(itemId, payload.string("delta").orEmpty())
            "conversation.item.input_audio_transcription.completed" -> Event.UserTranscriptDone(itemId, payload.string("transcript").orEmpty())
            "response.text.delta" -> Event.TextDelta(itemId, payload.string("delta").orEmpty())
            "response.audio_transcript.delta" -> Event.AudioTranscriptDelta(itemId, payload.string("delta").orEmpty())
            "response.thinking.delta" -> Event.ThinkingDelta(itemId, payload.string("delta").orEmpty())
            "response.audio.delta" -> Event.AudioDelta(itemId, payload.string("delta").orEmpty())
            "response.audio.done" -> Event.AudioDone
            "response.function_call_arguments.done" -> Event.FunctionArgumentsDone(
                callId = payload.string("call_id").orEmpty(),
                name = payload.string("name").orEmpty(),
                arguments = payload.string("arguments").orEmpty(),
            )
            "response.done" -> Event.ResponseDone(
                responseId = payload["response"]?.jsonObject?.string("id"),
                status = payload["response"]?.jsonObject?.string("status"),
            )
            "response.created" -> Event.ResponseCreated
            "error", "invalid_request_error" -> Event.Error(
                eventId = payload.string("event_id"),
                message = payload["error"]?.jsonObject?.string("message")
                    ?: payload.string("message")
                    ?: "Realtime 服务返回未知错误",
            )
            else -> Event.Unknown(type)
        }
    }

    private fun clientEvent(
        type: String,
        builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        buildJsonObject {
            put("event_id", UUID.randomUUID().toString())
            put("type", type)
            builder()
        }

    private fun JsonObject.string(name: String): String? = this[name]
        ?.jsonPrimitive
        ?.contentOrNull
}
