package com.agent.voiceassistant.settings

import android.content.Context
import com.agent.voiceassistant.agent.LLMConfig
import com.agent.voiceassistant.agent.LlmProviderMode
import com.agent.voiceassistant.cloud.OpenAiCompatibleLlmClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

@Serializable
data class LlmProviderProfile(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val mode: LlmProviderMode,
    val builtIn: Boolean = false,
)

class LlmProviderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secrets = EncryptedSecretStore(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    fun profiles(): List<LlmProviderProfile> = listOf(builtInProfile()) + customProfiles()

    fun activeProfile(): LlmProviderProfile {
        val activeId = preferences.getString(KEY_ACTIVE, BUILT_IN_ID)
        return profiles().firstOrNull { it.id == activeId } ?: builtInProfile()
    }

    fun setActive(id: String) {
        require(profiles().any { it.id == id }) { "供应商不存在" }
        preferences.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun profile(id: String?): LlmProviderProfile? = profiles().firstOrNull { it.id == id }

    fun save(
        id: String?,
        displayName: String,
        baseUrl: String,
        modelId: String,
        mode: LlmProviderMode,
        apiKey: String?,
    ): LlmProviderProfile {
        val profileId = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        require(profileId != BUILT_IN_ID) { "内置供应商不能修改" }
        val normalized = normalizeBaseUrl(baseUrl)
        val profile = LlmProviderProfile(
            id = profileId,
            displayName = displayName.trim().also { require(it.isNotBlank()) { "请输入显示名称" } },
            baseUrl = normalized,
            modelId = modelId.trim().also { require(it.isNotBlank()) { "请输入模型 ID" } },
            mode = mode,
        )
        val normalizedApiKey = apiKey?.trim()?.takeIf { it.isNotBlank() }
        require(normalizedApiKey != null || hasApiKey(profileId)) { "请输入 API Key" }
        val all = customProfiles().filterNot { it.id == profileId } + profile
        preferences.edit().putString(KEY_PROFILES, json.encodeToString(all)).apply()
        normalizedApiKey?.let { secrets.put(secretName(profileId), it) }
        return profile
    }

    fun delete(id: String) {
        if (id == BUILT_IN_ID) return
        preferences.edit()
            .putString(KEY_PROFILES, json.encodeToString(customProfiles().filterNot { it.id == id }))
            .apply()
        secrets.remove(secretName(id))
        if (preferences.getString(KEY_ACTIVE, BUILT_IN_ID) == id) setActive(BUILT_IN_ID)
    }

    fun hasApiKey(id: String): Boolean = when (id) {
        BUILT_IN_ID -> LLMConfig.stepFun().apiKey.isNotBlank()
        else -> !secrets.get(secretName(id)).isNullOrBlank()
    }

    fun runtimeConfig(profile: LlmProviderProfile = activeProfile(), overrideApiKey: String? = null): LLMConfig {
        if (profile.builtIn) return LLMConfig.stepFun()
        val apiKey = overrideApiKey?.trim()?.takeIf { it.isNotBlank() }
            ?: secrets.get(secretName(profile.id)).orEmpty()
        require(apiKey.isNotBlank()) { "供应商未配置 API Key" }
        return LLMConfig(
            apiKey = apiKey,
            baseUrl = profile.baseUrl,
            modelName = profile.modelId,
            providerMode = profile.mode,
        )
    }

    suspend fun test(profile: LlmProviderProfile, overrideApiKey: String? = null): String {
        val client = OpenAiCompatibleLlmClient(runtimeConfig(profile, overrideApiKey))
        return try {
            client.testConnection()
        } finally {
            client.close()
        }
    }

    private fun customProfiles(): List<LlmProviderProfile> {
        val raw = preferences.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<LlmProviderProfile>>(raw) }.getOrDefault(emptyList())
    }

    private fun builtInProfile(): LlmProviderProfile {
        val config = LLMConfig.stepFun()
        return LlmProviderProfile(
            id = BUILT_IN_ID,
            displayName = "小米 MiMo（内置）",
            baseUrl = config.baseUrl,
            modelId = config.modelName,
            mode = LlmProviderMode.MIMO,
            builtIn = true,
        )
    }

    private fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("Base URL 格式无效")
        require(uri.scheme == "https" || uri.scheme == "http") { "Base URL 必须使用 HTTP 或 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Base URL 缺少主机名" }
        return normalized
    }

    private fun secretName(id: String) = "llm.$id.api_key"

    companion object {
        const val BUILT_IN_ID = "builtin-mimo"
        private const val PREFERENCES = "llm_provider_settings"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active_profile"
    }
}
