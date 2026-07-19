package com.agent.voiceassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenReplyPolicyTest {
    @Test
    fun `keeps complete long reply while removing markdown`() {
        val input = """
            **第一条** 这是第一条资讯，包含了完整背景、关键结论、事件时间、影响范围以及后续值得继续关注的变化。

            **第二条** 这是第二条资讯，也必须完整播报，不能因为超过一百二十字就被截断，尤其不能让用户只听到前半段而误以为任务已经汇报完成。

            **第三条** 这是最后一条资讯，用来验证长文本尾部仍然存在，并确认完整内容最终能够送入 TTS 管线。
        """.trimIndent()

        val spoken = SpokenReplyPolicy.fallback(input)

        assertTrue(spoken.length > 120)
        assertTrue(spoken.contains("第一条资讯"))
        assertTrue(spoken.contains("第二条资讯"))
        assertTrue(spoken.contains("最后一条资讯"))
        assertFalse(spoken.contains("**"))
    }

    @Test
    fun `keeps fenced details on screen but not in speech`() {
        val input = """
            结论已经确认。
            ```json
            {"status":"ok","secret":"not spoken"}
            ```
        """.trimIndent()

        val spoken = SpokenReplyPolicy.fallback(input)

        assertTrue(spoken.startsWith("结论已经确认"))
        assertTrue(spoken.endsWith(SpokenReplyPolicy.DETAILS_NOTICE))
        assertFalse(spoken.contains("secret"))
    }

    @Test
    fun `details only response has no speakable text`() {
        val input = """
            ```xml
            <result>detail</result>
            ```
        """.trimIndent()

        assertTrue(SpokenReplyPolicy.isDetailsOnly(input))
        assertTrue(SpokenReplyPolicy.fallback(input).isEmpty())
    }
}
