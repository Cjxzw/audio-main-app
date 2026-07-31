package com.agent.voiceassistant.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class HubTaskReportTransitionTest {
    @Test
    fun `historical terminal task establishes baseline without notification`() {
        assertEquals(
            TaskReportState.NONE.name,
            nextHubReportState(null, null, TaskStatus.COMPLETED.name),
        )
        assertEquals(
            TaskReportState.NONE.name,
            nextHubReportState(null, null, TaskStatus.FAILED.name),
        )
    }

    @Test
    fun `active task entering terminal state becomes pending`() {
        assertEquals(
            TaskReportState.PENDING.name,
            nextHubReportState(TaskStatus.RUNNING.name, TaskReportState.NONE.name, TaskStatus.COMPLETED.name),
        )
        assertEquals(
            TaskReportState.PENDING.name,
            nextHubReportState(TaskStatus.QUEUED.name, TaskReportState.NONE.name, TaskStatus.FAILED.name),
        )
    }

    @Test
    fun `repeated terminal snapshot preserves report state`() {
        assertEquals(
            TaskReportState.REPORTED.name,
            nextHubReportState(TaskStatus.COMPLETED.name, TaskReportState.REPORTED.name, TaskStatus.COMPLETED.name),
        )
        assertEquals(
            TaskReportState.PENDING.name,
            nextHubReportState(TaskStatus.FAILED.name, TaskReportState.PENDING.name, TaskStatus.FAILED.name),
        )
    }

    @Test
    fun `non terminal updates never enter report queue`() {
        assertEquals(
            TaskReportState.NONE.name,
            nextHubReportState(TaskStatus.RUNNING.name, TaskReportState.NONE.name, TaskStatus.RUNNING.name),
        )
        assertEquals(
            TaskReportState.NONE.name,
            nextHubReportState(TaskStatus.BLOCKED.name, TaskReportState.NONE.name, TaskStatus.QUEUED.name),
        )
    }
}
