package com.agent.voiceassistant.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReportPolicyTest {
    @Test
    fun `report style keeps audio concise and moves technical detail to display block`() {
        val instructions = TaskReportPolicy.styleInstructions()

        assertTrue(instructions.contains("不超过三句"))
        assertTrue(instructions.contains("代码函数名"))
        assertTrue(instructions.contains("结果”仍必须压缩成一句话"))
        assertTrue(instructions.contains("<DETAILS>...</DETAILS>"))
    }

    @Test
    fun `active idle conversation reports through audio`() {
        assertEquals(
            TaskReportPolicy.Route.ACTIVE_AUDIO,
            TaskReportPolicy.route(false, true, true, false),
        )
    }

    @Test
    fun `active user speech is never interrupted`() {
        assertEquals(
            TaskReportPolicy.Route.DEFER,
            TaskReportPolicy.route(false, true, true, true),
        )
    }

    @Test
    fun `dormant session needs external output for audio`() {
        assertEquals(
            TaskReportPolicy.Route.NOTIFICATION,
            TaskReportPolicy.route(true, true, false, false),
        )
        assertEquals(
            TaskReportPolicy.Route.DORMANT_EXTERNAL_AUDIO,
            TaskReportPolicy.route(true, true, true, false),
        )
    }

    @Test
    fun `active session without external output uses notification`() {
        assertEquals(
            TaskReportPolicy.Route.NOTIFICATION,
            TaskReportPolicy.route(false, true, false, false),
        )
    }

    @Test
    fun `different conversation is notification only`() {
        assertEquals(
            TaskReportPolicy.Route.NOTIFICATION,
            TaskReportPolicy.route(false, false, true, false),
        )
    }

    @Test
    fun `global speech opt out forces notification while active`() {
        assertEquals(
            TaskReportPolicy.Route.NOTIFICATION,
            TaskReportPolicy.route(
                dormant = false,
                sameConversation = true,
                externalOutputConnected = true,
                userSpeaking = false,
                audioReportsEnabled = false,
            ),
        )
    }
}
