package com.agent.voiceassistant.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningInactivityPolicyTest {
    @Test
    fun `warns after the first idle window`() {
        assertEquals(
            ListeningInactivityPolicy.Action.WARN,
            ListeningInactivityPolicy.action(10_000L, false, 10_000L, 5_000L),
        )
    }

    @Test
    fun `sleeps after the second idle window`() {
        assertEquals(
            ListeningInactivityPolicy.Action.SLEEP,
            ListeningInactivityPolicy.action(5_000L, true, 10_000L, 5_000L),
        )
    }

    @Test
    fun `continues while either window is still active`() {
        assertEquals(
            ListeningInactivityPolicy.Action.CONTINUE,
            ListeningInactivityPolicy.action(9_999L, false, 10_000L, 5_000L),
        )
        assertEquals(
            ListeningInactivityPolicy.Action.CONTINUE,
            ListeningInactivityPolicy.action(4_999L, true, 10_000L, 5_000L),
        )
    }

    @Test
    fun `follow up window is capped by the total sleep deadline`() {
        assertEquals(5_000L, ListeningInactivityPolicy.remainingUntilSleep(15_000L, 10_000L))
        assertEquals(1_250L, ListeningInactivityPolicy.remainingUntilSleep(15_000L, 13_750L))
        assertEquals(0L, ListeningInactivityPolicy.remainingUntilSleep(15_000L, 15_500L))
    }
}
