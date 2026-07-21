package com.agent.voiceassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalConversationCommandPolicyTest {
    @Test
    fun `recognizes explicit sleep commands despite punctuation`() {
        listOf("退下吧。", "拜拜！", "没事儿了", "你走开", "滚蛋", "进入休眠").forEach { text ->
            assertEquals(
                LocalConversationCommandPolicy.Command.SLEEP,
                LocalConversationCommandPolicy.classify(text),
            )
        }
    }

    @Test
    fun `does not sleep for discussion containing a sleep phrase`() {
        assertNull(LocalConversationCommandPolicy.classify("你为什么会说再见？"))
        assertNull(LocalConversationCommandPolicy.classify("休眠功能是怎么实现的"))
        assertNull(LocalConversationCommandPolicy.classify("我没事了才怪"))
    }

    @Test
    fun `keeps new topic command support`() {
        assertEquals(
            LocalConversationCommandPolicy.Command.NEW_TOPIC,
            LocalConversationCommandPolicy.classify(" /new "),
        )
        assertEquals(
            LocalConversationCommandPolicy.Command.NEW_TOPIC,
            LocalConversationCommandPolicy.classify("开启新话题。"),
        )
    }
}
