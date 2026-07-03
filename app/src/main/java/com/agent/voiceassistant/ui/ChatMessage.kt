package com.agent.voiceassistant.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChatRole { USER, BOT, SYSTEM }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val timeStr: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))
}
