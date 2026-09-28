package com.agent.voiceassistant.cloud.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimePlainTextPolicyTest {
    @Test
    fun `removes common markdown presentation markers`() {
        val text = """
            ## 能力
            - **天气**：晴
            - [官网](https://example.com)
        """.trimIndent()

        assertEquals("能力\n天气：晴\n官网（https://example.com）", RealtimePlainTextPolicy.normalize(text))
    }
}
