package com.agent.voiceassistant.tasks

import android.content.Context
import com.agent.voiceassistant.hub.HubTaskFact
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal val HUB_REPORTABLE_TERMINAL_STATUSES = setOf(
    TaskStatus.COMPLETED.name,
    TaskStatus.FAILED.name,
    TaskStatus.INTERRUPTED.name,
)

private val HUB_FINAL_STATUSES = HUB_REPORTABLE_TERMINAL_STATUSES + TaskStatus.CANCELLED.name

internal fun nextHubReportState(
    previousStatus: String?,
    previousReportState: String?,
    incomingStatus: String,
): String = when {
    incomingStatus !in HUB_REPORTABLE_TERMINAL_STATUSES -> TaskReportState.NONE.name
    previousStatus == null -> TaskReportState.NONE.name
    previousStatus in HUB_FINAL_STATUSES -> previousReportState ?: TaskReportState.NONE.name
    else -> TaskReportState.PENDING.name
}

class TaskRepository(context: Context) {
    private val dao = TaskDatabase.get(context).taskDao()
    private val json = Json

    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()

    suspend fun get(taskId: String): TaskEntity? = dao.get(taskId)

    suspend fun latestActive(conversationId: String): TaskEntity? = dao.latestActive(conversationId)

    suspend fun create(submission: TaskSubmission): Pair<TaskEntity, Boolean> {
        dao.findByIdempotencyKey(submission.idempotencyKey)?.let { return it to false }
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            taskId = "task_${UUID.randomUUID()}",
            idempotencyKey = submission.idempotencyKey,
            taskType = submission.taskType,
            title = submission.title,
            executorId = submission.executorId,
            executorName = submission.executorName,
            conversationId = submission.conversationId,
            sourceTurnId = submission.sourceTurnId,
            priority = submission.priority.name,
            status = TaskStatus.QUEUED.name,
            inputJson = submission.inputJson,
            createdAt = now,
            updatedAt = now,
        )
        return runCatching {
            dao.insert(task)
            dao.insertEvent(TaskEventEntity(taskId = task.taskId, type = "created", createdAt = now))
            task to true
        }.getOrElse {
            dao.findByIdempotencyKey(submission.idempotencyKey)?.let { existing -> existing to false }
                ?: throw it
        }
    }

    suspend fun upsertHubFact(fact: HubTaskFact) {
        if (fact.taskId.isBlank()) return
        val now = System.currentTimeMillis()
        val status = when (fact.status.lowercase()) {
            "completed" -> TaskStatus.COMPLETED.name
            "failed" -> TaskStatus.FAILED.name
            "interrupted" -> TaskStatus.INTERRUPTED.name
            "cancelled" -> TaskStatus.CANCELLED.name
            "blocked" -> TaskStatus.BLOCKED.name
            "running" -> TaskStatus.RUNNING.name
            "sent", "created" -> TaskStatus.QUEUED.name
            else -> TaskStatus.QUEUED.name
        }
        val terminal = status in HUB_REPORTABLE_TERMINAL_STATUSES
        dao.upsertHubTask(
            TaskEntity(
                taskId = fact.taskId,
                idempotencyKey = "hub:${fact.taskId}",
                taskType = "hub_remote",
                title = fact.title.ifBlank { "Hub 远程任务" },
                origin = TaskOrigin.HUB.name,
                executorId = fact.agentId,
                executorName = fact.agentId,
                conversationId = "default",
                sourceTurnId = "hub",
                priority = TaskPriority.NORMAL.name,
                status = status,
                progress = if (terminal) 100 else if (status == TaskStatus.RUNNING.name) 50 else 0,
                inputJson = "{}",
                summary = fact.summary,
                details = fact.details,
                error = fact.failureReason.orEmpty(),
                remoteRevision = fact.updatedAt.hashCode().toLong(),
                reportState = TaskReportState.NONE.name,
                createdAt = now,
                startedAt = if (status == TaskStatus.RUNNING.name) now else null,
                completedAt = if (terminal) now else null,
                updatedAt = now,
            ),
        )
    }

    suspend fun registerHubDispatch(
        taskId: String,
        title: String,
        agentId: String,
        conversationId: String,
        sourceTurnId: String,
        priority: TaskPriority,
    ) {
        if (taskId.isBlank() || taskId == "unknown") return
        val now = System.currentTimeMillis()
        dao.bindHubDispatch(
            TaskEntity(
                taskId = taskId,
                idempotencyKey = "hub:$taskId",
                taskType = "hub_remote",
                title = title,
                origin = TaskOrigin.HUB.name,
                executorId = agentId,
                executorName = agentId,
                conversationId = conversationId,
                sourceTurnId = sourceTurnId,
                priority = priority.name,
                status = TaskStatus.QUEUED.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateResultContent(taskId: String, summary: String, details: String): TaskEntity? {
        dao.updateResultContent(taskId, summary, details, System.currentTimeMillis())
        return dao.get(taskId)
    }

    suspend fun markRunning(task: TaskEntity) {
        val now = System.currentTimeMillis()
        dao.updateState(
            task.taskId, TaskStatus.RUNNING.name, 0, "任务执行中", "", "", "",
            now, null, TaskReportState.NONE.name, now,
        )
        dao.insertEvent(TaskEventEntity(taskId = task.taskId, type = "running", createdAt = now))
    }

    suspend fun updateProgress(taskId: String, progress: Int) {
        dao.updateProgress(taskId, progress.coerceIn(0, 99), System.currentTimeMillis())
    }

    suspend fun complete(taskId: String, result: TaskExecutionResult) {
        val now = System.currentTimeMillis()
        dao.updateState(
            taskId, TaskStatus.COMPLETED.name, 100, result.summary, result.details, "", result.outputPath,
            null, now, TaskReportState.PENDING.name, now,
        )
        if (result.artifacts.isNotEmpty()) dao.insertArtifacts(result.artifacts)
        dao.insertEvent(TaskEventEntity(taskId = taskId, type = "completed", createdAt = now))
    }

    suspend fun fail(taskId: String, error: Throwable) {
        val now = System.currentTimeMillis()
        val message = error.message ?: error.javaClass.simpleName
        dao.updateState(
            taskId, TaskStatus.FAILED.name, 0, "任务执行失败", "", message, "",
            null, now, TaskReportState.PENDING.name, now,
        )
        dao.insertEvent(TaskEventEntity(taskId = taskId, type = "failed", payloadJson = message, createdAt = now))
    }

    suspend fun interrupt(taskId: String, reason: String) {
        val now = System.currentTimeMillis()
        dao.updateState(
            taskId, TaskStatus.INTERRUPTED.name, 0, "任务已中断", "", reason, "",
            null, now, TaskReportState.PENDING.name, now,
        )
        dao.insertEvent(TaskEventEntity(taskId = taskId, type = "interrupted", payloadJson = reason, createdAt = now))
    }

    suspend fun cancel(taskId: String): Boolean {
        val now = System.currentTimeMillis()
        val changed = dao.cancel(taskId, now) > 0
        if (changed) dao.insertEvent(TaskEventEntity(taskId = taskId, type = "cancelled", createdAt = now))
        return changed
    }

    suspend fun pendingReports(): List<TaskEntity> = dao.pendingReports()

    suspend fun interruptOrphanedRunning(): Int = dao.interruptRunning(System.currentTimeMillis())

    suspend fun recoverInterruptedReports(): Int = dao.recoverReporting(System.currentTimeMillis())

    suspend fun pruneHistory(retentionMs: Long = 30L * 24L * 60L * 60L * 1_000L): Int =
        dao.pruneHistory(System.currentTimeMillis() - retentionMs)

    suspend fun unfinished(): List<TaskEntity> = dao.unfinished()

    suspend fun beginReport(tasks: List<TaskEntity>, routeMode: String): TaskReportActionEntity {
        val now = System.currentTimeMillis()
        val action = TaskReportActionEntity(
            reportActionId = "report_${UUID.randomUUID()}",
            taskIdsJson = json.encodeToString(tasks.map(TaskEntity::taskId)),
            conversationId = tasks.first().conversationId,
            routeMode = routeMode,
            state = "STARTED",
            startedAt = now,
        )
        dao.beginReport(action, tasks.map(TaskEntity::taskId))
        return action
    }

    suspend fun completeReport(actionId: String, tasks: List<TaskEntity>, state: String, summaryText: String) {
        dao.completeReport(actionId, tasks.map(TaskEntity::taskId), state, summaryText, System.currentTimeMillis())
    }

    suspend fun failReport(actionId: String, tasks: List<TaskEntity>, error: String) {
        dao.failReport(actionId, tasks.map(TaskEntity::taskId), error, System.currentTimeMillis())
    }
}
