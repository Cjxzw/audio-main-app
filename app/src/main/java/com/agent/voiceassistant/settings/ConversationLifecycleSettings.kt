package com.agent.voiceassistant.settings

import android.content.Context

data class ConversationLifecycleConfig(
    val contextThresholdK: Int = 100,
    val inactiveMinutes: Int = 10,
)

class ConversationLifecycleSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): ConversationLifecycleConfig = ConversationLifecycleConfig(
        contextThresholdK = preferences.getInt(KEY_THRESHOLD_K, DEFAULT_THRESHOLD_K).coerceAtLeast(1),
        inactiveMinutes = preferences.getInt(KEY_INACTIVE_MINUTES, DEFAULT_INACTIVE_MINUTES).coerceAtLeast(1),
    )

    fun save(config: ConversationLifecycleConfig) {
        require(config.contextThresholdK > 0) { "上下文阈值必须是正整数" }
        require(config.inactiveMinutes > 0) { "非活跃时间必须是正整数" }
        preferences.edit()
            .putInt(KEY_THRESHOLD_K, config.contextThresholdK)
            .putInt(KEY_INACTIVE_MINUTES, config.inactiveMinutes)
            .apply()
    }

    companion object {
        const val DEFAULT_THRESHOLD_K = 100
        const val DEFAULT_INACTIVE_MINUTES = 10
        private const val PREFERENCES = "conversation_lifecycle_settings"
        private const val KEY_THRESHOLD_K = "context_threshold_k"
        private const val KEY_INACTIVE_MINUTES = "inactive_minutes"
    }
}
