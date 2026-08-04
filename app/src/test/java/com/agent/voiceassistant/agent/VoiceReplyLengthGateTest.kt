package com.agent.voiceassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReplyLengthGateTest {
    @Test
    fun countsHanAndEveryOtherCodePointSeparately() {
        assertFalse(VoiceReplyLengthGate.shouldSummarize("汉".repeat(49) + " \n\t!?🙂"))
        assertTrue(VoiceReplyLengthGate.shouldSummarize("汉".repeat(50)))
        assertTrue(VoiceReplyLengthGate.shouldSummarize(" ".repeat(50)))
    }

    @Test
    fun triggersOnlyAfterTheThreshold() {
        val gate = VoiceReplyLengthGate(50)

        assertFalse(gate.observe("甲".repeat(49)))
        assertTrue(gate.observe("乙"))
        assertFalse(gate.observe("丙"))
        assertTrue(gate.exceeded)
    }
}
