package com.agent.voiceassistant.tasks

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TaskOrigin { LOCAL, HUB }
enum class TaskPriority { NORMAL, URGENT }
enum class TaskStatus { CREATED, QUEUED, RUNNING, COMPLETED, FAILED, INTERRUPTED, CANCELLED, BLOCKED }
enum class TaskReportState { PENDING, REPORTED, NONE }

@Entity(
    tableName = "tasks",
    indices = [Index(value = ["idempotency_key"], unique = true)],
)
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "task_type") val taskType: String,
    val title: String,
    val origin: String = TaskOrigin.LOCAL.name,
    @ColumnInfo(name = "executor_id") val executorId: String,
    @ColumnInfo(name = "executor_name") val executorName: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "source_turn_id") val sourceTurnId: String,
    val priority: String = TaskPriority.NORMAL.name,
    val status: String = TaskStatus.CREATED.name,
    val progress: Int = 0,
    @ColumnInfo(name = "input_json") val inputJson: String = "{}",
    val summary: String = "",
    val details: String = "",
    val error: String = "",
    @ColumnInfo(name = "output_path") val outputPath: String = "",
    @ColumnInfo(name = "remote_revision") val remoteRevision: Long = 0,
    @ColumnInfo(name = "report_state") val reportState: String = TaskReportState.NONE.name,
    @ColumnInfo(name = "reported_at") val reportedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "task_report_actions")
data class TaskReportActionEntity(
    @PrimaryKey @ColumnInfo(name = "report_action_id") val reportActionId: String,
    @ColumnInfo(name = "task_ids_json") val taskIdsJson: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "route_mode") val routeMode: String,
    val state: String,
    @ColumnInfo(name = "summary_text") val summaryText: String = "",
    val error: String = "",
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
)

@Entity(tableName = "task_artifacts", indices = [Index("task_id")])
data class TaskArtifactEntity(
    @PrimaryKey @ColumnInfo(name = "artifact_id") val artifactId: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val name: String,
    val path: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val size: Long,
    val sha256: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "task_events", indices = [Index("task_id")])
data class TaskEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: String,
    val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String = "{}",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

data class TaskSubmission(
    val taskType: String,
    val title: String,
    val executorId: String,
    val executorName: String,
    val conversationId: String,
    val sourceTurnId: String,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val inputJson: String,
    val idempotencyKey: String,
)

data class TaskExecutionResult(
    val summary: String,
    val details: String = "",
    val outputPath: String = "",
    val artifacts: List<TaskArtifactEntity> = emptyList(),
)
