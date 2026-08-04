package com.agent.voiceassistant.service

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.agent.voiceassistant.BuildConfig
import com.agent.voiceassistant.settings.AppCapabilityResolver
import com.agent.voiceassistant.settings.LlmProviderRepository
import com.agent.voiceassistant.settings.SpeechPreferences
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class DeviceContextProvider(
    context: Context,
    private val capabilities: AppCapabilityResolver,
    private val providers: LlmProviderRepository,
    private val speechPreferences: SpeechPreferences,
    private val locationContext: () -> String = { "位置缓存：暂无" },
) {
    private val appContext = context.applicationContext
    private val systemProperties = readSystemProperties()
    private val stable = buildStableContext()

    fun build(inputSource: String, network: String): String {
        val profile = providers.activeProfile()
        val capability = capabilities.capabilities()
        return buildString {
            appendLine("<device_context>")
            append(stable)
            appendLine("当前输入：${if (inputSource == "text") "文字" else if (inputSource == "voice") "语音" else inputSource}")
            appendLine("文本回合静音：${if (inputSource == "text" && speechPreferences.muteTextReplies) "开启" else "关闭"}")
            appendLine("网络：$network")
            appendLine("当前聊天模型：${profile.modelId}")
            appendLine("当前模型支持图片：${yesNo(profile.supportsImages)}")
            appendLine("默认多模态模型可用：${yesNo(capabilities.defaultLlmAvailable())}")
            appendLine("语音输入输出可用：${yesNo(capability.speechAvailable)}")
            appendLine("麦克风权限：${permission(Manifest.permission.RECORD_AUDIO)}")
            appendLine("位置权限：${if (permissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION) || permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) "已授权" else "未授权"}")
            appendLine(locationContext())
            appendLine("说明：设备元信息只用于理解运行环境和兼容性；实际权限与能力以本回合提供的工具为准。")
            append("</device_context>")
        }
    }

    private fun buildStableContext(): String = buildString {
        appendLine("平台：Android")
        appendLine("系统版本：Android ${Build.VERSION.RELEASE}")
        appendLine("系统 API：${Build.VERSION.SDK_INT}")
        appendLine("定制系统：${customOs()}")
        appendLine("设备厂商：${Build.MANUFACTURER.ifBlank { "未知" }}")
        appendLine("设备品牌：${Build.BRAND.ifBlank { "未知" }}")
        appendLine("设备型号：${Build.MODEL.ifBlank { "未知" }}")
        appendLine("设备类型：手机")
        appendLine("处理器：${socModel()}")
        appendLine("处理器标识：${Build.HARDWARE.ifBlank { "未知" }}")
        appendLine("CPU 架构：${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}")
        appendLine("总内存：${totalRamGb()}")
        appendLine("系统语言：${Locale.getDefault().toLanguageTag()}")
        appendLine("App 版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
    }

    private fun socModel(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.takeIf(String::isNotBlank) ?: Build.HARDWARE.ifBlank { "未知" }
    } else {
        Build.HARDWARE.ifBlank { "未知" }
    }

    private fun customOs(): String {
        val candidates = listOf(
            "HarmonyOS" to "ro.build.version.harmony",
            "HyperOS/MIUI" to "ro.mi.os.version.name",
            "MIUI" to "ro.miui.ui.version.name",
            "ColorOS" to "ro.build.version.opporom",
            "OriginOS" to "ro.vivo.os.version",
            "EMUI" to "ro.build.version.emui",
            "MagicOS" to "ro.build.version.magic",
            "One UI" to "ro.build.version.oneui",
            "REDMAGIC OS" to "ro.build.nubia.rom.name",
            "nubia UI" to "ro.nubia.ui.version",
        )
        candidates.forEach { (label, key) ->
            systemProperty(key).takeIf(String::isNotBlank)?.let { return "$label $it".take(120) }
        }
        return Build.DISPLAY.take(120).ifBlank { "未知" }
    }

    private fun systemProperty(key: String): String = systemProperties[key].orEmpty()

    private fun readSystemProperties(): Map<String, String> = runCatching {
        val binary = File("/system/bin/getprop")
        if (!binary.isFile) return@runCatching emptyMap()
        val process = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        GETPROP_LINE.findAll(output).associate { match ->
            match.groupValues[1] to match.groupValues[2].trim()
        }
    }.getOrDefault(emptyMap())

    private fun totalRamGb(): String {
        val info = ActivityManager.MemoryInfo()
        appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return "%.1f GB".format(Locale.ROOT, info.totalMem / (1024.0 * 1024.0 * 1024.0))
    }

    private fun permission(name: String) = if (permissionGranted(name)) "已授权" else "未授权"
    private fun permissionGranted(name: String) =
        ContextCompat.checkSelfPermission(appContext, name) == PackageManager.PERMISSION_GRANTED
    private fun yesNo(value: Boolean) = if (value) "是" else "否"

    private companion object {
        val GETPROP_LINE = Regex("(?m)^\\[([^]]+)]\\s*:\\s*\\[(.*)]$")
    }
}
