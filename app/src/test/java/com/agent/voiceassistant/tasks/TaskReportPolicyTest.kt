package com.agent.voiceassistant.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReportPolicyTest {
    @Test
    fun `summary style keeps audio concise and leaves details to the system`() {
        val instructions = TaskReportPolicy.summaryInstructions()

        assertTrue(instructions.contains("不超过三句"))
        assertTrue(instructions.contains("代码函数名"))
        assertTrue(instructions.contains("只输出摘要"))
        assertTrue(instructions.contains("不要输出 DETAILS 标签"))
    }

    @Test
    fun `system appends exact remote body in canonical details block`() {
        val task = task(details = "完成了三项修改。\n```json\n{\"ok\":true}\n```")

        assertEquals(
            "任务已经完成。\n\n<DETAILS>\n完成了三项修改。\n```json\n{\"ok\":true}\n```\n</DETAILS>",
            TaskReportPolicy.composeChatReport("任务已经完成。", listOf(task)),
        )
    }

    @Test
    fun `summary strips model details and is capped at three sentences`() {
        assertEquals(
            "第一句。第二句！第三句？",
            TaskReportPolicy.normalizeSummary(
                "第一句。第二句！第三句？第四句。<DETAILS>不应保留</DETAILS>",
                "备用总结。",
            ),
        )
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

    private fun task(details: String) = TaskEntity(
        taskId = "task-1",
        idempotencyKey = "hub:task-1",
        taskType = "hub_remote",
        title = "远程任务",
        origin = TaskOrigin.HUB.name,
        executorId = "agent-1",
        executorName = "agent-1",
        conversationId = "conversation-1",
        sourceTurnId = "turn-1",
        status = TaskStatus.COMPLETED.name,
        summary = "已完成",
        details = details,
        createdAt = 1L,
        updatedAt = 2L,
    )
}
