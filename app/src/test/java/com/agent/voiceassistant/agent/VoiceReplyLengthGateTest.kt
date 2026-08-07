package com.agent.voiceassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReplyLengthGateTest {
    @Test
    fun countsHanAndNonHanThresholdsIndependently() {
        assertFalse(VoiceReplyLengthGate.shouldSummarize("汉".repeat(49), threshold = 50))
        assertTrue(VoiceReplyLengthGate.shouldSummarize("汉".repeat(50), threshold = 50))
        assertTrue(VoiceReplyLengthGate.shouldSummarize("a".repeat(50), threshold = 50))
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
