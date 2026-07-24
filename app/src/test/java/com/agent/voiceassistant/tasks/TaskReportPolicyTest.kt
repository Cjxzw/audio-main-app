package com.agent.voiceassistant.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskReportPolicyTest {
    @Test
    fun `active idle conversation reports through audio`() {
        assertEquals(
            TaskReportPolicy.Route.ACTIVE_AUDIO,
            TaskReportPolicy.route(false, true, false, false),
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
    fun `different conversation is notification only`() {
        assertEquals(
            TaskReportPolicy.Route.NOTIFICATION,
            TaskReportPolicy.route(false, false, true, false),
        )
    }
}
