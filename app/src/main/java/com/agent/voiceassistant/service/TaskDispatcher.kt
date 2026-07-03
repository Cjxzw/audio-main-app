package com.agent.voiceassistant.service

import com.agent.voiceassistant.report.DispatchedTask
import com.agent.voiceassistant.report.PendingResult
import com.agent.voiceassistant.report.Priority
import com.agent.voiceassistant.report.TaskListSummary
import com.agent.voiceassistant.report.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * TaskDispatcher：任务派发器。
 *
 * 职责：
 * - 接收 AgentTools.dispatchTask 调用，生成 [DispatchedTask] 入 [TaskListSummary]
 * - 通过 WebSocket / HTTP 把任务发送到 PC 端 Coordinator Agent（第三期实现）
 * - 接收 PC 端回传的任务结果，更新状态并通知 [PendingResultReporter]
 *
 * MVP 阶段：
 * - 仅维护任务列表，模拟 PC 端立即完成（用于验证汇报流程）
 * - 第三期接入真实 WebSocket 通信
 *
 * 工程约定（来自项目记忆）：
 * - 任务报告原子性：报告内容和状态更新必须封装在单个事务中
 * - 任务列表显示：所有 in_progress + 所有 unreported + 最新 5 条 reported
 */
class TaskDispatcher(
    private val scope: CoroutineScope,
    private val taskList: TaskListSummary = TaskListSummary(),
    /** 完成回调（注入汇报队列）。可后续通过 [bindReporter] 替换 */
    private var onTaskCompleted: ((PendingResult) -> Unit)? = null,
    /** MVP 模拟 PC 完成延迟（毫秒）。设为 0 关闭模拟。 */
    private val simulateDelayMs: Long = 5_000L
) {
    /**
     * 绑定汇报回调。用于"先建 dispatcher 再建 reporter"的初始化顺序。
     */
    fun bindReporter(reporter: com.agent.voiceassistant.report.PendingResultReporter) {
        onTaskCompleted = { pending ->
            reporter.enqueueResult(pending)
        }
        Timber.i("TaskDispatcher: reporter bound")
    }
    /**
     * 提交任务，立即返回 task_id。
     * @return task_id
     */
    fun submit(
        taskType: String,
        description: String,
        priority: Priority = Priority.NORMAL
    ): String {
        val taskId = "task-${UUID.randomUUID().toString().take(8)}"
        val task = DispatchedTask(
            id = taskId,
            taskType = taskType,
            description = description,
            priority = priority
        )
        taskList.add(task)
        Timber.i("TaskDispatcher: submitted $taskId (type=$taskType, priority=$priority)")

        // MVP 阶段：模拟 PC 端延迟完成
        if (onTaskCompleted != null && simulateDelayMs > 0) {
            scope.launch(Dispatchers.Default) {
                delay(simulateDelayMs)
                // 模拟一个简单结果
                val fakeSummary = when (taskType) {
                    "research" -> "调研完成：找到 3 条相关资料"
                    "analysis" -> "分析完成：关键指标已汇总"
                    "coding" -> "代码已生成，约 200 行"
                    "summary" -> "已生成摘要，约 200 字"
                    else -> "$taskType 任务已完成"
                }
                completeTask(taskId, fakeSummary)
            }
        }
        return taskId
    }

    /**
     * 完成（或失败）任务：更新状态 + 触发汇报回调。
     * 这是"报告内容 + 状态更新"的原子操作入口。
     */
    fun completeTask(
        taskId: String,
        summary: String,
        status: TaskStatus = TaskStatus.completed,
        fullReport: String? = null
    ) {
        val updated = taskList.update(taskId) { task ->
            task.status = status
            task.summary = summary
            task.fullReport = fullReport
            task.completedAt = System.currentTimeMillis()
        }
        if (updated == null) {
            Timber.w("TaskDispatcher: task $taskId not found, cannot complete")
            return
        }
        Timber.i("TaskDispatcher: completed $taskId (status=$status)")

        // 触发汇报队列
        if (updated.needsReport) {
            val pending = updated.toPendingResult()
            onTaskCompleted?.invoke(pending)
        }
    }

    /** 查询任务 */
    fun getTask(taskId: String): DispatchedTask? = taskList.get(taskId)

    /** 暴露任务列表（供 UI 显示） */
    fun taskList(): TaskListSummary = taskList

    /** 列表摘要文本（供 LLM 上下文使用） */
    fun summaryText(): String = taskList.summaryText()
}
