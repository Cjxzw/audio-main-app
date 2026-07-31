package com.agent.voiceassistant

import android.app.Application
import com.agent.voiceassistant.hub.HubRuntime
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口：初始化日志与全局配置。
 *
 * 日志同时输出到：
 * 1. Logcat（Timber.DebugTree，调试构建）
 * 2. 文件：filesDir/agent-runtime/logs/voice-agent.log（自动轮转）
 * 3. 崩溃栈：filesDir/agent-runtime/logs/voice-agent-crash.log（仅崩溃时写入）
 *
 * 读取方式：adb shell run-as com.agent.voiceassistant cat files/agent-runtime/logs/voice-agent.log
 */
class App : Application() {

    private val logDir: File by lazy { File(filesDir, "agent-runtime/logs").apply(File::mkdirs) }
    val logFile: File by lazy { File(logDir, "voice-agent.log") }

    override fun onCreate() {
        super.onCreate()

        HubRuntime.initialize(this)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 始终种植文件日志树
        Timber.plant(FileLogTree(logFile))

        // 全局崩溃捕获
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashFile = File(logDir, "voice-agent-crash.log")
            try {
                PrintWriter(FileWriter(crashFile)).use { pw ->
                    pw.println("=== CRASH at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())} ===")
                    pw.println("Thread: ${thread.name}")
                    pw.println("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                    pw.println()
                    throwable.printStackTrace(pw)
                }
            } catch (_: Exception) {}

            // 同时写入日志文件
            try {
                FileWriter(logFile, true).use { fw ->
                    fw.appendLine("!!! CRASH: ${throwable.javaClass.name}: ${throwable.message}")
                    throwable.printStackTrace(PrintWriter(fw))
                }
            } catch (_: Exception) {}

            // 调用系统默认处理器
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Timber.i("VoiceAssistant App started. version=${BuildConfig.VERSION_NAME}")
    }

    /**
     * Timber Tree：将日志写入文件。
     * 格式：时间 级别/标签: 消息
     */
    private class FileLogTree(private val file: File) : Timber.Tree() {
        private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        private val lock = Any()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val level = when (priority) {
                android.util.Log.VERBOSE -> "V"
                android.util.Log.DEBUG -> "D"
                android.util.Log.INFO -> "I"
                android.util.Log.WARN -> "W"
                android.util.Log.ERROR -> "E"
                else -> "?"
            }
            val line = buildString {
                append(sdf.format(Date()))
                append(" ")
                append(level)
                append("/")
                append(tag ?: inferTag(message))
                append(": ")
                append(message)
                if (t != null) {
                    append("\n")
                    append(android.util.Log.getStackTraceString(t))
                }
                append("\n")
            }
            synchronized(lock) {
                try {
                    rotateIfNeeded(line.toByteArray().size)
                    FileWriter(file, true).use { it.write(line) }
                } catch (_: Exception) {}
            }
        }

        private fun inferTag(message: String): String {
            val normalized = message.trimStart()
            return when {
                normalized.startsWith("Latency LLM") || normalized.startsWith("LLM") -> "VA_LLM"
                normalized.startsWith("Latency TTS") || normalized.startsWith("TTS") -> "VA_TTS"
                normalized.startsWith("Cloud ASR") || normalized.startsWith("CloudRecorder") ||
                    normalized.startsWith("SimpleVad") -> "VA_ASR"
                normalized.startsWith("Audio") || normalized.startsWith("Earcon") ||
                    normalized.startsWith("MediaPlayer") -> "VA_AUDIO"
                normalized.startsWith("LocalToolExecutor") || normalized.startsWith("调用工具") ||
                    normalized.startsWith("工具结果") -> "VA_TOOL"
                normalized.startsWith("SongTask") || normalized.startsWith("Task") -> "VA_TASK"
                normalized.startsWith("Location") -> "VA_LOCATION"
                normalized.startsWith("Network") || normalized.startsWith("HTTP") -> "VA_NETWORK"
                normalized.startsWith("UI") || normalized.startsWith("LogBus") -> "VA_UI"
                else -> "VA_APP"
            }
        }

        private fun rotateIfNeeded(incomingBytes: Int) {
            if (file.length() + incomingBytes <= MAX_LOG_BYTES) return
            for (index in MAX_LOG_FILES - 1 downTo 1) {
                val source = if (index == 1) file else File(file.parentFile, "${file.name}.${index - 1}")
                val target = File(file.parentFile, "${file.name}.$index")
                if (source.exists()) {
                    target.delete()
                    source.renameTo(target)
                }
            }
        }

        private companion object {
            private const val MAX_LOG_BYTES = 512 * 1024L
            private const val MAX_LOG_FILES = 4
        }
    }
}
