package com.agent.voiceassistant.report

import java.util.UUID

/**
 * PendingResult：后台任务完成后等待汇报的结果。
 *
 * 设计要点（参考 GLaDOS Slot）：
 * - [taskId] 由 TaskDispatcher 分配
 * - [summary] 用于语音汇报的简短摘要（控制长度避免一次播报过长）
 * - [priority] 决定汇报时机（URGENT 可打断，NORMAL 闲时，LOW 延后）
 * - [fullReport] 完整报告（可选，存文件供用户查询）
 * - [reported] 与 [reportedAt] 用于追踪是否已汇报
 *
 * 工程约定（来自项目记忆）：
 * - 必须有 reported boolean 字段和 reported_at timestamp
 */
data class PendingResult(
    val taskId: String = UUID.randomUUID().toString(),
    val taskType: String,
    val summary: String,
    val priority: Priority = Priority.NORMAL,
    val fullReport: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    var reported: Boolean = false,
    var reportedAt: Long? = null
)
