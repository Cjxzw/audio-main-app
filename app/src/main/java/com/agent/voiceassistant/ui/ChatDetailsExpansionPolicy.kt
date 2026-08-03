package com.agent.voiceassistant.ui

import com.agent.voiceassistant.agent.ReplyDetailPolicy

object ChatDetailsExpansionPolicy {
    const val RECENT_ROUNDS = 3

    fun defaultExpanded(messages: List<ChatMessage>, position: Int): Boolean {
        val message = messages.getOrNull(position) ?: return false
        if (message.role != ChatRole.BOT || !ReplyDetailPolicy.extract(message.text).hasMarkedDetails) {
            return false
        }
        val userRounds = messages.mapIndexedNotNull { index, item ->
            index.takeIf { item.role == ChatRole.USER && item.toolCallId.isNullOrBlank() }
        }
        val messageRound = userRounds.indexOfLast { it <= position }
        if (messageRound < 0) return true
        return userRounds.lastIndex - messageRound < RECENT_ROUNDS
    }
}
