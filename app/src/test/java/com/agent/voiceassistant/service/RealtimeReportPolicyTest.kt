package com.agent.voiceassistant.service

import org.junit.Assert.*
import org.junit.Test

class RealtimeReportPolicyTest {
    @Test fun waitsForSpeechResponseAndToolsBeforeReporting() {
        assertFalse(RealtimeReportPolicy.canReport(true, true, true, 0, false))
        assertFalse(RealtimeReportPolicy.canReport(true, false, true, 0, false))
        assertFalse(RealtimeReportPolicy.canReport(true, false, false, 1, false))
        assertFalse(RealtimeReportPolicy.canReport(true, false, false, 0, true))
        assertTrue(RealtimeReportPolicy.canReport(true, false, false, 0, false))
    }
    @Test fun connectionAndWarmupMustFinishFirst() {
        assertFalse(RealtimeReportPolicy.canReport(false, false, false, 0, false))
    }
}
