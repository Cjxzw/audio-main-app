package com.agent.voiceassistant.tasks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query(
        """
        SELECT * FROM tasks
        ORDER BY
          CASE status WHEN 'RUNNING' THEN 0 WHEN 'QUEUED' THEN 1 ELSE 2 END,
          updated_at DESC
        """,
    )
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE task_id = :taskId")
    suspend fun get(taskId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE conversation_id = :conversationId AND status IN ('CREATED', 'QUEUED', 'RUNNING') ORDER BY created_at DESC LIMIT 1")
    suspend fun latestActive(conversationId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE idempotency_key = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtifacts(artifacts: List<TaskArtifactEntity>)

    @Insert
    suspend fun insertEvent(event: TaskEventEntity)

    @Query(
        """
        UPDATE tasks SET status = :status, progress = :progress, summary = :summary,
          details = :details, error = :error, output_path = :outputPath,
          started_at = COALESCE(started_at, :startedAt), completed_at = :completedAt,
          report_state = :reportState, updated_at = :updatedAt
        WHERE task_id = :taskId
        """,
    )
    suspend fun updateState(
        taskId: String,
        status: String,
        progress: Int,
        summary: String,
        details: String,
        error: String,
        outputPath: String,
        startedAt: Long?,
        completedAt: Long?,
        reportState: String,
        updatedAt: Long,
    ): Int

    @Query("UPDATE tasks SET progress = :progress, updated_at = :updatedAt WHERE task_id = :taskId AND status = 'RUNNING'")
    suspend fun updateProgress(taskId: String, progress: Int, updatedAt: Long): Int

    @Query(
        """
        SELECT * FROM tasks
        WHERE report_state = 'PENDING'
          AND status IN ('COMPLETED', 'FAILED', 'INTERRUPTED')
        ORDER BY CASE priority WHEN 'URGENT' THEN 0 ELSE 1 END, completed_at ASC
        """,
    )
    suspend fun pendingReports(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun unfinished(): List<TaskEntity>

    @Query("SELECT task_id FROM tasks WHERE completed_at IS NOT NULL AND completed_at < :cutoff AND status IN ('COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELLED')")
    suspend fun terminalTaskIdsBefore(cutoff: Long): List<String>

    @Query("DELETE FROM task_artifacts WHERE task_id IN (:taskIds)")
    suspend fun deleteArtifacts(taskIds: List<String>)

    @Query("DELETE FROM task_events WHERE task_id IN (:taskIds)")
    suspend fun deleteEvents(taskIds: List<String>)

    @Query("DELETE FROM tasks WHERE task_id IN (:taskIds)")
    suspend fun deleteTasks(taskIds: List<String>)

    @Query("DELETE FROM task_report_actions WHERE finished_at IS NOT NULL AND finished_at < :cutoff")
    suspend fun deleteReportActionsBefore(cutoff: Long)

    @Transaction
    suspend fun pruneHistory(cutoff: Long): Int {
        val ids = terminalTaskIdsBefore(cutoff)
        if (ids.isNotEmpty()) {
            deleteArtifacts(ids)
            deleteEvents(ids)
            deleteTasks(ids)
        }
        deleteReportActionsBefore(cutoff)
        return ids.size
    }

    @Query(
        """
        UPDATE tasks SET status = 'INTERRUPTED', summary = '任务因 App 进程中断而停止',
          error = 'process_interrupted', completed_at = :now, updated_at = :now,
          report_state = 'PENDING'
        WHERE status = 'RUNNING'
        """,
    )
    suspend fun interruptRunning(now: Long): Int

    @Query("UPDATE tasks SET status = 'CANCELLED', summary = '任务已取消', completed_at = :now, updated_at = :now, report_state = 'NONE' WHERE task_id = :taskId AND status IN ('CREATED', 'QUEUED', 'RUNNING')")
    suspend fun cancel(taskId: String, now: Long): Int

    @Insert
    suspend fun insertReportAction(action: TaskReportActionEntity)

    @Query("UPDATE tasks SET report_state = 'REPORTED', reported_at = :now, updated_at = :now WHERE task_id IN (:taskIds) AND report_state = 'PENDING'")
    suspend fun markReported(taskIds: List<String>, now: Long): Int

    @Query("UPDATE task_report_actions SET state = :state, summary_text = :summaryText, error = :error, finished_at = :finishedAt WHERE report_action_id = :actionId")
    suspend fun finishReportAction(actionId: String, state: String, summaryText: String, error: String, finishedAt: Long): Int

    @Transaction
    suspend fun beginReport(action: TaskReportActionEntity, taskIds: List<String>) {
        require(taskIds.isNotEmpty())
        insertReportAction(action)
        check(markReported(taskIds, action.startedAt) == taskIds.size) {
            "待汇报任务状态已变化"
        }
        taskIds.forEach { taskId ->
            insertEvent(TaskEventEntity(taskId = taskId, type = "report_started", createdAt = action.startedAt))
        }
    }
}
