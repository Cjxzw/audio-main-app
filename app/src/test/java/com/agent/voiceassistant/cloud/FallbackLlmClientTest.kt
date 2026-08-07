package com.agent.voiceassistant.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FallbackLlmClientTest {
    @Test
    fun fallsBackWhenPrimaryFailsBeforeOutput() = runBlocking {
        var activated = false
        val client = FallbackLlmClient(
            primary = FakeClient(error = IOException("timeout")),
            fallbackProvider = { FakeClient(text = "默认模型回答") },
            onFallback = { activated = true },
        )

        val completion = client.streamChat(request()) {}

        assertTrue(activated)
        assertEquals("默认模型回答", completion.message.content)
    }

    @Test(expected = IOException::class)
    fun doesNotFallBackAfterPrimaryProducedOutput() {
        runBlocking {
            var fallbackCreated = false
            val client = FallbackLlmClient(
                primary = FakeClient(error = IOException("broken stream"), emitFirst = true),
                fallbackProvider = {
                    fallbackCreated = true
                    FakeClient(text = "不应调用")
                },
                onFallback = {},
            )

            try {
                client.streamChat(request()) {}
            } finally {
                assertTrue(!fallbackCreated)
            }
        }
    }

    @Test
    fun fallsBackWhenPrimaryOnlyProducedReasoning() = runBlocking {
        var activated = false
        val client = FallbackLlmClient(
            primary = FakeClient(reasoningOnly = true),
            fallbackProvider = { FakeClient(text = "默认模型回答") },
            onFallback = { activated = true },
        )

        val completion = client.streamChat(request()) {}

        assertTrue(activated)
        assertEquals("默认模型回答", completion.message.content)
    }

    private fun request() = CloudSpeechClient.ChatRequest(
        messages = listOf(CloudSpeechClient.LlmMessage("user", "你好")),
        tools = emptyList(),
        thinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
        maxCompletionTokens = 100,
    )

    private class FakeClient(
        private val text: String? = null,
        private val error: IOException? = null,
        private val emitFirst: Boolean = false,
        private val reasoningOnly: Boolean = false,
    ) : LlmClient {
        override suspend fun streamChat(
            request: CloudSpeechClient.ChatRequest,
            onEvent: suspend (CloudSpeechClient.ChatStreamEvent) -> Unit,
        ): CloudSpeechClient.ChatCompletion {
            if (emitFirst) onEvent(CloudSpeechClient.ChatStreamEvent.ContentDelta("部分"))
            if (reasoningOnly) {
                onEvent(CloudSpeechClient.ChatStreamEvent.ReasoningDelta("内部分析"))
                return CloudSpeechClient.ChatCompletion(
                    CloudSpeechClient.LlmMessage("assistant", reasoningContent = "内部分析"),
                    "stop",
                )
            }
            error?.let { throw it }
            text?.let { onEvent(CloudSpeechClient.ChatStreamEvent.ContentDelta(it)) }
            return CloudSpeechClient.ChatCompletion(CloudSpeechClient.LlmMessage("assistant", text), "stop")
        }

        override suspend fun testConnection() = "ok"
    }
}
