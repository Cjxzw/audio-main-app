package com.agent.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSessionCompactorTest {
    private val compactor = RealtimeSessionCompactor()

    @Test
    fun `instruction asks for useful durable information only`() {
        val prompt = compactor.instruction("用户：下周提醒我检查合同\n助手：好的")

        assertTrue(prompt.contains("只保留下一次主会话可能需要的事实"))
        assertTrue(prompt.contains("下周提醒我检查合同"))
    }

    @Test
    fun `strict parser accepts empty digest`() {
        assertEquals("", compactor.parseStrict("{\"digest\":\"\"}").getOrThrow())
    }

    @Test
    fun `strict parser rejects extra fields and markdown`() {
        assertTrue(compactor.parseStrict("{\"digest\":\"x\",\"extra\":1}").isFailure)
        assertTrue(compactor.parseStrict("```json {\"digest\":\"x\"} ```").isFailure)
    }
}
