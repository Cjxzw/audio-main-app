package com.agent.voiceassistant.service

import org.junit.Assert.*
import org.junit.Test

class BackgroundTurnNotificationPolicyTest {
    @Test fun foregroundAndInternalWorkStayQuiet() {
        assertFalse(BackgroundTurnNotificationPolicy.shouldNotify(true, true, "turn"))
        assertFalse(BackgroundTurnNotificationPolicy.shouldNotify(false, false, "turn"))
        assertFalse(BackgroundTurnNotificationPolicy.shouldNotify(true, false, ""))
        assertTrue(BackgroundTurnNotificationPolicy.shouldNotify(true, false, "turn"))
    }

    @Test fun realtimeSuppressesNotificationsEvenWhenScreenIsOff() {
        assertFalse(BackgroundTurnNotificationPolicy.shouldNotify(true, false, "turn", realtimeActive = true))
        assertTrue(BackgroundTurnNotificationPolicy.shouldNotify(true, false, "turn", realtimeActive = false))
    }

    @Test fun failureFallbackCannotAnnounceSuccess() {
        assertNull(BackgroundTurnNotificationPolicy.completedStatus(true))
        assertEquals("任务已完成", BackgroundTurnNotificationPolicy.completedStatus(false))
    }
}
