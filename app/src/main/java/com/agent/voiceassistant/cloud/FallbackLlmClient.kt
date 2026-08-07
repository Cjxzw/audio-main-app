package com.agent.voiceassistant.cloud

import java.io.IOException

/** Switches to the built-in model only when the primary failed before producing output. */
class FallbackLlmClient(
    private val primary: LlmClient,
    private val fallbackProvider: () -> LlmClient,
    private val onFallback: suspend (Throwable) -> Unit,
) : LlmClient {
    private var fallback: LlmClient? = null

    override suspend fun streamChat(
        request: CloudSpeechClient.ChatRequest,
        onEvent: suspend (CloudSpeechClient.ChatStreamEvent) -> Unit,
    ): CloudSpeechClient.ChatCompletion {
        fallback?.let { return it.streamChat(request, onEvent) }
        var producedOutput = false
        return try {
            val completion = primary.streamChat(request) { event ->
                if (event is CloudSpeechClient.ChatStreamEvent.ContentDelta ||
                    event is CloudSpeechClient.ChatStreamEvent.ToolCallDelta
                ) {
                    producedOutput = true
                }
                onEvent(event)
            }
            if (completion.message.content.isNullOrBlank() && completion.message.toolCalls.isEmpty()) {
                throw EmptyLlmResponseException()
            }
            completion
        } catch (error: Throwable) {
            if (producedOutput || error !is IOException) throw error
            val replacement = fallbackProvider()
            fallback = replacement
            primary.close()
            onFallback(error)
            replacement.streamChat(request, onEvent)
        }
    }

    override suspend fun testConnection(): String = primary.testConnection()

    override fun close() {
        primary.close()
        fallback?.close()
    }
}

private class EmptyLlmResponseException : IOException("自定义 LLM 返回了空响应")
