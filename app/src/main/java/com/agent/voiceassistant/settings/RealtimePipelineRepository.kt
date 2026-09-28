package com.agent.voiceassistant.settings

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class VoicePipeline {
    MIMO_STANDARD,
    STEPFUN_REALTIME,
}

@Serializable
data class StepFunRealtimeConfig(
    val modelId: String = RealtimePipelineRepository.DEFAULT_MODEL_ID,
    val voice: String = RealtimePipelineRepository.DEFAULT_VOICE,
    val serverVadEnabled: Boolean = true,
    val vadSilenceDurationMs: Int = RealtimePipelineRepository.DEFAULT_VAD_SILENCE_DURATION_MS,
)

/**
 * Owns only the selected voice pipeline and StepFun Realtime configuration.
 * Traditional chat providers remain available for background memory compaction.
 */
class RealtimePipelineRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secrets = EncryptedSecretStore(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    fun activePipeline(): VoicePipeline = preferences
        .getString(KEY_ACTIVE_PIPELINE, VoicePipeline.MIMO_STANDARD.name)
        ?.let { value -> runCatching { VoicePipeline.valueOf(value) }.getOrNull() }
        ?: VoicePipeline.MIMO_STANDARD

    fun setActivePipeline(pipeline: VoicePipeline) {
        preferences.edit().putString(KEY_ACTIVE_PIPELINE, pipeline.name).apply()
    }

    fun stepFunConfig(): StepFunRealtimeConfig {
        val raw = preferences.getString(KEY_STEPFUN_CONFIG, null) ?: return StepFunRealtimeConfig()
        return runCatching { json.decodeFromString<StepFunRealtimeConfig>(raw) }
            .getOrDefault(StepFunRealtimeConfig())
    }

    fun saveStepFunConfig(config: StepFunRealtimeConfig, apiKey: String? = null) {
        val normalized = config.copy(
            modelId = config.modelId.trim().also { require(it.isNotBlank()) { "请输入 StepFun 模型 ID" } },
            voice = config.voice.trim().also { require(it.isNotBlank()) { "请输入 StepFun 音色" } },
            vadSilenceDurationMs = config.vadSilenceDurationMs.coerceIn(MIN_VAD_SILENCE_DURATION_MS, MAX_VAD_SILENCE_DURATION_MS),
        )
        val normalizedKey = apiKey?.trim()?.takeIf { it.isNotBlank() }
        require(normalizedKey != null || hasStepFunKey()) { "请输入 StepFun API Key" }
        preferences.edit().putString(KEY_STEPFUN_CONFIG, json.encodeToString(normalized)).apply()
        normalizedKey?.let { secrets.put(STEPFUN_API_KEY_SECRET, it) }
    }

    fun stepFunApiKey(): String = secrets.get(STEPFUN_API_KEY_SECRET).orEmpty().trim()

    fun hasStepFunKey(): Boolean = stepFunApiKey().isNotBlank()

    fun clearStepFunKey() = secrets.remove(STEPFUN_API_KEY_SECRET)

    companion object {
        const val ENDPOINT = "wss://api.stepfun.com/v1/realtime"
        const val DEFAULT_MODEL_ID = "stepaudio-3-realtime-preview"
        const val DEFAULT_VOICE = "qingchunshaonv"
        const val DEFAULT_VAD_SILENCE_DURATION_MS = 500
        const val MIN_VAD_SILENCE_DURATION_MS = 200
        const val MAX_VAD_SILENCE_DURATION_MS = 2_000

        private const val PREFERENCES = "realtime_pipeline_settings"
        private const val KEY_ACTIVE_PIPELINE = "active_voice_pipeline"
        private const val KEY_STEPFUN_CONFIG = "stepfun_realtime_config"
        private const val STEPFUN_API_KEY_SECRET = "stepfun.realtime.api_key"
    }
}
