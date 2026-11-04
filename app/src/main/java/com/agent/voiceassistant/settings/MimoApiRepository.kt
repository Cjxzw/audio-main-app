package com.agent.voiceassistant.settings

import android.content.Context
import com.agent.voiceassistant.agent.LLMConfig

enum class MimoKeyType(val label: String, val baseUrl: String) {
    PAY_AS_YOU_GO("按量付费", "https://api.xiaomimimo.com/v1"),
    TOKEN_PLAN("套餐 Token Plan", "https://token-plan-cn.xiaomimimo.com/v1"),
}

data class AppCapabilities(
    val llmAvailable: Boolean,
    val speechAvailable: Boolean,
    val usingCustomLlm: Boolean,
) {
    val summary: String
        get() = when {
            llmAvailable -> "已配置大模型供应商；语音使用 Realtime 管线"
            else -> "尚未配置大模型供应商"
        }
}

class MimoApiRepository(context: Context) {
    private val secrets = EncryptedSecretStore(context.applicationContext)

    fun apiKey(): String = secrets.get(SECRET_NAME).orEmpty().trim()

    fun keyType(): MimoKeyType? = detectKeyType(apiKey())

    fun hasValidKey(): Boolean = keyType() != null

    fun saveKey(value: String) {
        val normalized = value.trim()
        require(detectKeyType(normalized) != null) { "MiMo Key 必须以 sk 或 tp 开头" }
        secrets.put(SECRET_NAME, normalized)
    }

    fun clearKey() = secrets.remove(SECRET_NAME)

    fun runtimeConfig(modelId: String = DEFAULT_MODEL): LLMConfig {
        val key = apiKey()
        val type = detectKeyType(key) ?: throw IllegalStateException("请先配置有效的 MiMo API Key")
        return LLMConfig.mimo(key, type.baseUrl, modelId)
    }

    companion object {
        const val DEFAULT_MODEL = "mimo-v2.6-pro"
        private const val SECRET_NAME = "mimo.api_key"

        fun detectKeyType(value: String): MimoKeyType? = when {
            value.trim().startsWith("sk", ignoreCase = true) -> MimoKeyType.PAY_AS_YOU_GO
            value.trim().startsWith("tp", ignoreCase = true) -> MimoKeyType.TOKEN_PLAN
            else -> null
        }
    }
}

class AppCapabilityResolver(context: Context) {
    private val mimo = MimoApiRepository(context)
    private val llmProviders = LlmProviderRepository(context)

    fun capabilities(): AppCapabilities {
        val active = llmProviders.activeProfile()
        val customReady = !active.builtIn && llmProviders.hasApiKey(active.id)
        val mimoReady = mimo.hasValidKey()
        return AppCapabilities(
            llmAvailable = customReady,
            speechAvailable = false,
            usingCustomLlm = customReady,
        )
    }

    fun llmConfig(): LLMConfig {
        val active = llmProviders.activeProfile()
        return llmProviders.runtimeConfig(active)
    }

    fun defaultLlmAvailable(): Boolean = false

    fun defaultLlmConfig(): LLMConfig = throw IllegalStateException("未配置默认 LLM")

    fun speechConfig(): LLMConfig = mimo.runtimeConfig()
}
