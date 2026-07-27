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
) {
    val timeStr: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))
}
