package com.agent.voiceassistant.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class VadThresholdPolicyTest {
    @Test
    fun `speech during calibration cannot raise noise floor indefinitely`() {
        assertEquals(0.003f, VadThresholdPolicy.boundedNoiseFloor(0.75f, 15), 0.00001f)
    }

    @Test
    fun `speech peak raises end of utterance threshold`() {
        assertEquals(0.0126f, VadThresholdPolicy.stopThreshold(0.0014f, 0.07f), 0.00001f)
    }

    @Test
    fun `quiet speech retains calibrated end threshold`() {
        assertEquals(0.0014f, VadThresholdPolicy.stopThreshold(0.0014f, 0.004f), 0.00001f)
    }
}
