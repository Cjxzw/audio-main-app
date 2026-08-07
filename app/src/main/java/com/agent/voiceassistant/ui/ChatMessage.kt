package com.agent.voiceassistant.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChatRole { USER, BOT, SYSTEM }

enum class ToolDisplayStatus { RUNNING, SUCCEEDED, FAILED }

enum class ChatPresentation { STANDARD, PERSONALIZED_VOICE }

enum class ChatStreamState { STREAMING, COMPLETED, INTERRUPTED }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val messageId: String? = null,
    val toolCallId: String? = null,
    val toolStatus: ToolDisplayStatus? = null,
    val presentation: ChatPresentation = ChatPresentation.STANDARD,
    val streamState: ChatStreamState? = null,
    val reasoningText: String? = null,
    val modelId: String? = null,
    val promptTokens: Long? = null,
    val contextWindowTokens: Long? = null,
    val promptTokensEstimated: Boolean = false,
) {
    val timeStr: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))

    val metadataStr: String
        get() = listOfNotNull(
            timeStr,
            modelId?.takeIf(String::isNotBlank),
            promptTokens?.let { used ->
                val prefix = if (promptTokensEstimated) "~" else ""
                "$prefix${formatTokens(used)}/${contextWindowTokens?.let(::formatTokens) ?: "?"}"
            },
        ).joinToString("   ")

    private fun formatTokens(tokens: Long): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000}k"
        tokens >= 1_000 -> "${tokens / 1_000}k"
        else -> tokens.toString()
    }
}
