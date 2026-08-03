package com.agent.voiceassistant.tasks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

interface TaskExecutor {
    val taskType: String
    suspend fun execute(task: TaskEntity, progress: suspend (Int) -> Unit): TaskExecutionResult
    suspend fun cleanup(task: TaskEntity) = Unit
}

class AsyncTaskCoordinator(
    private val repository: TaskRepository,
    private val scope: CoroutineScope,
) {
    private val executors = ConcurrentHashMap<String, TaskExecutor>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun register(executor: TaskExecutor) {
        executors[executor.taskType] = executor
    }

    suspend fun recover() {
        val pruned = repository.pruneHistory()
        if (pruned > 0) Timber.i("AsyncTasks: pruned $pruned expired task records")
        val reports = repository.recoverInterruptedReports()
        if (reports > 0) Timber.w("AsyncTasks: restored $reports interrupted task reports")
        val interrupted = repository.interruptOrphanedRunning()
        if (interrupted > 0) Timber.w("AsyncTasks: marked $interrupted orphaned running tasks interrupted")
        repository.unfinished()
            .filter { it.status == TaskStatus.QUEUED.name }
            .forEach(::launch)
    }

    suspend fun submit(submission: TaskSubmission): TaskEntity {
        val (task, created) = repository.create(submission)
        if (created) launch(task)
        return task
    }

    suspend fun cancel(taskId: String): Boolean {
        val task = repository.get(taskId) ?: return false
        val changed = repository.cancel(taskId)
        if (changed) {
            jobs.remove(taskId)?.cancel()
            runCatching { executors[task.taskType]?.cleanup(task) }
                .onFailure { Timber.w(it, "AsyncTasks: cleanup failed task=$taskId") }
        }
        return changed
    }

    suspend fun get(taskId: String): TaskEntity? = repository.get(taskId)

    suspend fun latestActive(conversationId: String): TaskEntity? = repository.latestActive(conversationId)

    private fun launch(task: TaskEntity) {
        if (jobs[task.taskId]?.isActive == true) return
        val executor = executors[task.taskType]
        if (executor == null) {
            scope.launch { repository.fail(task.taskId, IllegalStateException("没有可用执行器：${task.taskType}")) }
            return
        }
        jobs[task.taskId] = scope.launch {
            try {
                repository.markRunning(task)
                val result = executor.execute(task) { value -> repository.updateProgress(task.taskId, value) }
                if (repository.get(task.taskId)?.status != TaskStatus.CANCELLED.name) {
                    repository.complete(task.taskId, result)
                }
            } catch (cancelled: CancellationException) {
                if (repository.get(task.taskId)?.status != TaskStatus.CANCELLED.name) {
                    repository.interrupt(task.taskId, "execution_cancelled")
                }
                throw cancelled
            } catch (error: Throwable) {
                Timber.e(error, "AsyncTasks: execution failed task=${task.taskId}")
                repository.fail(task.taskId, error)
            } finally {
                runCatching { executor.cleanup(task) }
                    .onFailure { Timber.w(it, "AsyncTasks: final cleanup failed task=${task.taskId}") }
                jobs.remove(task.taskId)
            }
        }
    }
}
