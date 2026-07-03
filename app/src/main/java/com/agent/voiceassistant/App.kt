package com.agent.voiceassistant

import android.app.Application
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
 * 2. 文件：filesDir/voice-agent.log（所有构建，adb run-as 可读）
 * 3. 崩溃栈：filesDir/voice-agent-crash.log（仅崩溃时写入）
 *
 * 读取方式：adb shell run-as com.agent.voiceassistant cat files/voice-agent.log
 */
class App : Application() {

    val logFile: File by lazy {
        File(filesDir, "voice-agent.log")
    }

    override fun onCreate() {
        super.onCreate()

        // 清空旧日志
        try { logFile.writeText("") } catch (_: Exception) {}

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 始终种植文件日志树
        Timber.plant(FileLogTree(logFile))

        // 全局崩溃捕获
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashFile = File(filesDir, "voice-agent-crash.log")
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
                append(tag ?: "?")
                append(": ")
                append(message)
                if (t != null) {
                    append("\n")
                    append(android.util.Log.getStackTraceString(t))
                }
                append("\n")
            }
            try {
                FileWriter(file, true).use { it.write(line) }
            } catch (_: Exception) {}
        }
    }
}
