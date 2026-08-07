package com.agent.voiceassistant.cloud

import com.agent.voiceassistant.agent.LLMConfig
import com.agent.voiceassistant.agent.LlmProviderMode
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmClientTest {
    @Test
    fun `mimo payload uses native thinking contract`() {
        val payload = client(LlmProviderMode.MIMO).buildChatPayload(request())

        assertTrue(payload.containsKey("max_completion_tokens"))
        assertTrue(payload.getValue("thinking").jsonObject.containsKey("type"))
        assertFalse(payload.containsKey("max_tokens"))
    }

    @Test
    fun `generic openai payload avoids mimo-only fields`() {
        val payload = client(LlmProviderMode.OPENAI_COMPATIBLE).buildChatPayload(request())

        assertTrue(payload.containsKey("max_tokens"))
        assertFalse(payload.containsKey("thinking"))
        assertFalse(payload.containsKey("max_completion_tokens"))
    }

    @Test
    fun `deepseek payload enables native thinking and json object output`() {
        val request = request().copy(responseFormat = CloudSpeechClient.ResponseFormat.JSON_OBJECT)
        val payload = client(LlmProviderMode.OPENAI_COMPATIBLE, "deepseek-v4-flash").buildChatPayload(request)

        assertEquals("enabled", payload.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "json_object",
            payload.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `image input uses openai multimodal content array`() {
        val request = CloudSpeechClient.ChatRequest(
            messages = listOf(
                CloudSpeechClient.LlmMessage(
                    role = "user",
                    content = "看一下图片",
                    imageInputs = listOf(CloudSpeechClient.ImageInput("image/jpeg", "YWJj")),
                ),
            ),
            tools = emptyList(),
            thinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
            maxCompletionTokens = 128,
        )

        val content = client(LlmProviderMode.MIMO).buildChatPayload(request)
            .getValue("messages").jsonArray.single().jsonObject
            .getValue("content").jsonArray

        assertTrue(content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "text" })
        val image = content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }.jsonObject
        assertTrue(image.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `models response reports configured model when present`() {
        val response = """{"object":"list","data":[{"id":"test-model"},{"id":"another-model"}]}"""

        assertEquals(
            "模型列表可用，已找到 test-model",
            client(LlmProviderMode.OPENAI_COMPATIBLE).summarizeModelsResponse(response),
        )
    }

    @Test
    fun `models response accepts a root array`() {
        val response = """[{"id":"one"},{"id":"two"}]"""

        assertEquals(
            "模型列表可用，共 2 个模型",
            client(LlmProviderMode.OPENAI_COMPATIBLE).summarizeModelsResponse(response),
        )
    }

    private fun request() = CloudSpeechClient.ChatRequest(
        messages = listOf(CloudSpeechClient.LlmMessage("user", "测试")),
        tools = emptyList(),
        thinkingMode = CloudSpeechClient.ThinkingMode.ENABLED,
        maxCompletionTokens = 512,
    )

    private fun client(mode: LlmProviderMode, model: String = "test-model") = OpenAiCompatibleLlmClient(
        LLMConfig(
            apiKey = "test",
            baseUrl = "https://example.com/v1",
            modelName = model,
            providerMode = mode,
        ),
    )
}
