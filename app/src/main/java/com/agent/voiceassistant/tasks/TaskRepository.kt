package com.agent.voiceassistant.tasks

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

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

    suspend fun finishReport(actionId: String, state: String, summaryText: String = "", error: String = "") {
        dao.finishReportAction(actionId, state, summaryText, error, System.currentTimeMillis())
    }
}
