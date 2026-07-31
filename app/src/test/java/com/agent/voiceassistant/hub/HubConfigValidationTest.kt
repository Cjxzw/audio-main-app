package com.agent.voiceassistant.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class HubConfigValidationTest {
    @Test
    fun `base url normalization removes trailing slash only`() {
        assertEquals(
            "http://jxzw.ltd:50080",
            HubSettings.normalizeBaseUrl("  http://jxzw.ltd:50080///  "),
        )
        assertEquals("http://jxzw.ltd:50080", HubSettings.DEFAULT_BASE_URL)
    }
}
