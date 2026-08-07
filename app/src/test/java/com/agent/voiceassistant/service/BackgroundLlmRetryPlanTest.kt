package com.agent.voiceassistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLlmRetryPlanTest {
    @Test
    fun `custom provider gets two captured attempts then one default attempt`() {
        assertFalse(BackgroundLlmRetryPlan.usesDefaultProvider(capturedProviderIsBuiltIn = false, attempt = 1))
        assertFalse(BackgroundLlmRetryPlan.usesDefaultProvider(capturedProviderIsBuiltIn = false, attempt = 2))
        assertTrue(BackgroundLlmRetryPlan.usesDefaultProvider(capturedProviderIsBuiltIn = false, attempt = 3))
    }

    @Test
    fun `built in provider gets all three attempts on default`() {
        (1..BackgroundLlmRetryPlan.ATTEMPT_COUNT).forEach { attempt ->
            assertTrue(BackgroundLlmRetryPlan.usesDefaultProvider(capturedProviderIsBuiltIn = true, attempt = attempt))
        }
    }
}
