package com.agent.voiceassistant.report

/**
 * 任务状态枚举（来自项目记忆约定）。
 */
enum class TaskStatus {
    pending,
    in_progress,
    completed,
    failed
}

/**
 * DispatchedTask：已派出的任务。
 *
 * 工程约定（来自项目记忆）：
 * - 任务状态：pending → in_progress → completed/failed
 * - reported 字段：boolean + reported_at timestamp
 * - 任务报告原子性：报告内容和状态更新必须封装在单个事务中
 *
 * Coordinator 架构约定：
 * - 常驻加载任务列表摘要（所有 in_progress + 所有 unreported + 最新 5 条 reported）
 * - 按需加载 agent 列表
 */
data class DispatchedTask(
    val id: String,
    val taskType: String,
    val description: String,
    val priority: Priority = Priority.NORMAL,
    var status: TaskStatus = TaskStatus.pending,
    var summary: String? = null,
    var fullReport: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,
    var reported: Boolean = false,
    var reportedAt: Long? = null
) {
    /** 是否需要汇报（已完成但未汇报） */
    val needsReport: Boolean
        get() = (status == TaskStatus.completed || status == TaskStatus.failed) && !reported

    /** 转为 PendingResult 供汇报队列使用 */
    fun toPendingResult(): PendingResult = PendingResult(
        taskId = id,
        taskType = taskType,
        summary = summary ?: "任务 $id 已完成",
        priority = priority,
        fullReport = fullReport,
        timestamp = completedAt ?: System.currentTimeMillis(),
        reported = reported,
        reportedAt = reportedAt
    )
}
