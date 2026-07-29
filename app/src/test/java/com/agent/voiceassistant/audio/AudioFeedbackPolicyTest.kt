package com.agent.voiceassistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFeedbackPolicyTest {
    @Test
    fun mutedTextTurnSuppressesAllAutomaticFeedback() {
        assertFalse(AudioFeedbackPolicy.allowAutomaticFeedback("text", muteTextTurns = true))
        assertFalse(AudioFeedbackPolicy.allowProactiveTaskReports(muteTextTurns = true))
        assertTrue(AudioFeedbackPolicy.taskRequiresSilentReport("{\"_silent_audio\":true}"))
    }

    @Test
    fun voiceTurnStillAllowsFeedback() {
        assertTrue(AudioFeedbackPolicy.allowAutomaticFeedback("voice", muteTextTurns = true))
    }
}
