package com.agent.voiceassistant.cloud

import com.agent.voiceassistant.agent.LLMConfig
import com.agent.voiceassistant.agent.LlmProviderMode
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
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

    private fun request() = CloudSpeechClient.ChatRequest(
        messages = listOf(CloudSpeechClient.LlmMessage("user", "测试")),
        tools = emptyList(),
        thinkingMode = CloudSpeechClient.ThinkingMode.ENABLED,
        maxCompletionTokens = 512,
    )

    private fun client(mode: LlmProviderMode) = OpenAiCompatibleLlmClient(
        LLMConfig(
            apiKey = "test",
            baseUrl = "https://example.com/v1",
            modelName = "test-model",
            providerMode = mode,
        ),
    )
}
