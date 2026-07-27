package com.agent.voiceassistant.cloud

import com.agent.voiceassistant.agent.LLMConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamAccumulatorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `aggregates reasoning and fragmented native tool calls`() {
        val accumulator = ChatStreamAccumulator()

        accumulator.accept(chunk("""{"reasoning_content":"需要查询"}"""))
        accumulator.accept(
            chunk(
                """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\":"}}]}""",
            ),
        )
        accumulator.accept(
            chunk(
                """{"tool_calls":[{"index":0,"function":{"arguments":"\"MiMo 最新消息\"}"}}]}""",
                finishReason = "tool_calls",
            ),
        )

        val completion = accumulator.complete()
        assertEquals("tool_calls", completion.finishReason)
        assertEquals("需要查询", completion.message.reasoningContent)
        assertEquals(1, completion.message.toolCalls.size)
        assertEquals("call_1", completion.message.toolCalls.single().id)
        assertEquals("web_search", completion.message.toolCalls.single().name)
        assertEquals("{\"query\":\"MiMo 最新消息\"}", completion.message.toolCalls.single().arguments)
    }

    @Test
    fun `keeps reasoning separate from user visible content`() {
        val accumulator = ChatStreamAccumulator()
        val reasoningEvents = accumulator.accept(chunk("""{"reasoning_content":"内部分析"}"""))
        val contentEvents = accumulator.accept(chunk("""{"content":"最终回答"}""", finishReason = "stop"))

        assertTrue(reasoningEvents.single() is CloudSpeechClient.ChatStreamEvent.ReasoningDelta)
        assertTrue(contentEvents.first() is CloudSpeechClient.ChatStreamEvent.ContentDelta)
        assertEquals("最终回答", accumulator.complete().message.content)
        assertEquals("内部分析", accumulator.complete().message.reasoningContent)
    }

    @Test
    fun `payload explicitly disables thinking for fast turns`() {
        val client = CloudSpeechClient(
            LLMConfig(
                apiKey = "test",
                baseUrl = "https://example.com/v1",
                modelName = "mimo-v2.5",
            ),
        )
        val payload = client.buildChatPayload(
            CloudSpeechClient.ChatRequest(
                messages = listOf(CloudSpeechClient.LlmMessage("user", "你好")),
                tools = emptyList(),
                thinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
                maxCompletionTokens = 1_024,
            ),
        )

        assertEquals("disabled", payload["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNotNull(payload["temperature"])
    }

    @Test
    fun `deep tool history passes reasoning content and omits temperature`() {
        val client = CloudSpeechClient(
            LLMConfig(
                apiKey = "test",
                baseUrl = "https://example.com/v1",
                modelName = "mimo-v2.5",
            ),
        )
        val toolCall = CloudSpeechClient.ToolCall("call_1", "weather_get_current", "{}")
        val payload = client.buildChatPayload(
            CloudSpeechClient.ChatRequest(
                messages = listOf(
                    CloudSpeechClient.LlmMessage("user", "查天气"),
                    CloudSpeechClient.LlmMessage(
                        role = "assistant",
                        content = "",
                        reasoningContent = "需要先调用天气工具",
                        toolCalls = listOf(toolCall),
                    ),
                    CloudSpeechClient.LlmMessage(
                        role = "tool",
                        content = "晴，25度",
                        toolCallId = "call_1",
                    ),
                ),
                tools = emptyList(),
                thinkingMode = CloudSpeechClient.ThinkingMode.ENABLED,
                maxCompletionTokens = 4_096,
            ),
        )

        val messages = payload["messages"]!!.jsonArray
        val assistant = messages[1].jsonObject
        val toolResult = messages[2].jsonObject
        assertEquals("需要先调用天气工具", assistant["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("call_1", assistant["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("call_1", toolResult["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("enabled", payload["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("temperature"))
    }

    @Test(expected = MalformedToolCallException::class)
    fun `payload rejects malformed tool arguments before network request`() {
        val client = CloudSpeechClient(
            LLMConfig(
                apiKey = "test",
                baseUrl = "https://example.com/v1",
                modelName = "mimo-v2.5",
            ),
        )

        client.buildChatPayload(
            CloudSpeechClient.ChatRequest(
                messages = listOf(
                    CloudSpeechClient.LlmMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            CloudSpeechClient.ToolCall("call_bad", "read", "{\"path\":\"unterminated"),
                        ),
                    ),
                ),
                tools = emptyList(),
                thinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
                maxCompletionTokens = 1_024,
            ),
        )
    }

    @Test
    fun `tts payload requests connected slightly faster speech and streaming`() {
        val client = CloudSpeechClient(
            LLMConfig(
                apiKey = "test",
                baseUrl = "https://example.com/v1",
                modelName = "mimo-v2.5",
            ),
        )
        val payload = client.buildTtsPayload("第一句。第二句。", stream = true)
        val messages = payload["messages"]!!.jsonArray
        val style = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        val audio = payload["audio"]!!.jsonObject

        assertEquals(true, payload["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("pcm16", audio["format"]!!.jsonPrimitive.content)
        assertEquals("冰糖", audio["voice"]!!.jsonPrimitive.content)
        assertTrue(style.contains("连贯"))
        assertTrue(style.contains("略快"))
        assertTrue(style.contains("不要每句话重新起调"))
    }

    private fun chunk(deltaJson: String, finishReason: String? = null): JsonObject {
        val finish = finishReason?.let { "\"$it\"" } ?: "null"
        return json.parseToJsonElement(
            """{"choices":[{"index":0,"delta":$deltaJson,"finish_reason":$finish}]}""",
        ).jsonObject
    }
}
