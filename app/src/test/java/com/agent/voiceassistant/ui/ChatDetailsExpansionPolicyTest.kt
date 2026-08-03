package com.agent.voiceassistant.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDetailsExpansionPolicyTest {
    @Test
    fun `expands details in the three most recent user rounds`() {
        val messages = buildList {
            repeat(4) { round ->
                add(ChatMessage(ChatRole.USER, "问题 $round"))
                add(ChatMessage(ChatRole.BOT, "回答 $round\n<DETAILS>详情 $round</DETAILS>"))
            }
        }

        assertFalse(ChatDetailsExpansionPolicy.defaultExpanded(messages, 1))
        assertTrue(ChatDetailsExpansionPolicy.defaultExpanded(messages, 3))
        assertTrue(ChatDetailsExpansionPolicy.defaultExpanded(messages, 5))
        assertTrue(ChatDetailsExpansionPolicy.defaultExpanded(messages, 7))
    }
}
