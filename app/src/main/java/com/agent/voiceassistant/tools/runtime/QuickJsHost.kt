package com.agent.voiceassistant.tools.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface QuickJsHost {
    fun call(command: String, argsJson: String): String

    fun emit(text: String)
}

internal object QuickJsNative {
    init {
        System.loadLibrary("hanwo_quickjs")
    }

    external fun execute(
        source: String,
        inputJson: String,
        host: QuickJsHost,
        commands: Array<String>,
        timeoutMs: Int,
        memoryBytes: Long,
    ): String
}

class QuickJsRuntimeExecutor(
    private val commands: RuntimeCommandRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    data class Result(
        val valueJson: String,
        val output: String,
    )

    fun execute(
        source: String,
        inputJson: String = "null",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        memoryBytes: Long = DEFAULT_MEMORY_BYTES,
    ): Result {
        require(timeoutMs in 1_000..MAX_TIMEOUT_MS) { "timeout_ms 必须在 1000 到 $MAX_TIMEOUT_MS 之间" }
        require(memoryBytes in MIN_MEMORY_BYTES..MAX_MEMORY_BYTES) { "memory_bytes 超出允许范围" }
        val output = StringBuilder()
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        val host = object : QuickJsHost {
            override fun call(command: String, argsJson: String): String {
                val args = json.parseToJsonElement(argsJson) as? JsonObject
                    ?: error("运行时命令参数必须是 JSON 对象")
                val effectiveArgs = if (command == "http_request") {
                    val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
                    require(remainingMs > 0) { "QuickJS runtime timeout exceeded" }
                    buildJsonObject {
                        args.forEach { (key, value) -> put(key, value) }
                        val requestedMs = args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 5_000
                        put("timeout_ms", requestedMs.coerceAtMost(remainingMs).coerceIn(1, 120_000))
                    }
                } else {
                    require(System.nanoTime() < deadlineNanos) { "QuickJS runtime timeout exceeded" }
                    args
                }
                return try { commands.execute(command, effectiveArgs, deadlineNanos).value.toString() }
                catch (error: Exception) {
                    val code = when {
                        command !in commands.effectiveCommands -> "permission_denied"
                        error.message.orEmpty().contains("timeout") -> "runtime_timeout"
                        error is IllegalArgumentException -> "invalid_arguments"
                        else -> "command_failed"
                    }
                    throw IllegalStateException("$code command=$command keys=${args.keys.sorted().joinToString(",")}: ${error.message ?: error.javaClass.simpleName}")
                }
            }

            override fun emit(text: String) {
                if (output.length < MAX_OUTPUT_CHARS) {
                    output.append(text.take(MAX_OUTPUT_CHARS - output.length))
                }
            }
        }
        val result = QuickJsNative.execute(
            source, inputJson, host, commands.effectiveCommands.toTypedArray(), timeoutMs, memoryBytes,
        )
        return Result(result, output.toString())
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val MAX_TIMEOUT_MS = 120_000
        const val MIN_MEMORY_BYTES = 1L * 1_024 * 1_024
        const val DEFAULT_MEMORY_BYTES = 8L * 1_024 * 1_024
        const val MAX_MEMORY_BYTES = 64L * 1_024 * 1_024
        const val MAX_OUTPUT_CHARS = 40_000
    }
}
