package com.agent.voiceassistant.tasks

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class DelayedTestExecutor : TaskExecutor {
    override val taskType = TYPE
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(task: TaskEntity, progress: suspend (Int) -> Unit): TaskExecutionResult {
        val payload = json.parseToJsonElement(task.inputJson) as JsonObject
        val seconds = ((payload["delay_seconds"] as? JsonPrimitive)?.intOrNull ?: 30).coerceIn(1, 600)
        val shouldFail = (payload["fail"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        repeat(seconds) { elapsed ->
            delay(1_000)
            progress(((elapsed + 1) * 100 / seconds).coerceAtMost(99))
        }
        if (shouldFail) error("模拟异步任务按测试参数失败")
        return TaskExecutionResult(
            summary = "模拟异步任务已完成",
            details = "任务在后台运行了 $seconds 秒，期间没有阻塞当前对话。",
        )
    }

    companion object {
        const val TYPE = "delayed_test"
    }
}
