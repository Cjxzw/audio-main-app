package com.agent.voiceassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReplyLengthGateTest {
    @Test
    fun countsOnlyVisibleLettersAndDigits() {
        assertEquals(4, VoiceReplyLengthGate.countVisible("你好，世界！<DETAILS>隐藏内容</DETAILS>"))
        assertEquals(2, VoiceReplyLengthGate.countVisible("```json\n{\"ok\":true}\n```测试"))
    }

    @Test
    fun triggersOnlyAfterTheThreshold() {
        val gate = VoiceReplyLengthGate(30)

        assertFalse(gate.observe("甲".repeat(30)))
        assertTrue(gate.observe("乙"))
        assertFalse(gate.observe("丙"))
        assertTrue(gate.exceeded)
    }
}
