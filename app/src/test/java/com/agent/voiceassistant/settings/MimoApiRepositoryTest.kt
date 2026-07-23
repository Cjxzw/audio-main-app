package com.agent.voiceassistant.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MimoApiRepositoryTest {
    @Test
    fun detectsPayAsYouGoKeyAndBaseUrl() {
        val type = MimoApiRepository.detectKeyType("sk-example")

        assertEquals(MimoKeyType.PAY_AS_YOU_GO, type)
        assertEquals("https://api.xiaomimimo.com/v1", type?.baseUrl)
    }

    @Test
    fun detectsTokenPlanKeyAndBaseUrl() {
        val type = MimoApiRepository.detectKeyType("tp-example")

        assertEquals(MimoKeyType.TOKEN_PLAN, type)
        assertEquals("https://token-plan-cn.xiaomimimo.com/v1", type?.baseUrl)
    }

    @Test
    fun rejectsUnknownPrefixes() {
        assertNull(MimoApiRepository.detectKeyType("other-example"))
        assertNull(MimoApiRepository.detectKeyType(""))
    }
}
