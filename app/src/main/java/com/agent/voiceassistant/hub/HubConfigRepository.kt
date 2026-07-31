package com.agent.voiceassistant.hub

import android.content.Context
import android.provider.Settings
import com.agent.voiceassistant.settings.EncryptedSecretStore
import java.util.Locale

class HubConfigRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secrets = EncryptedSecretStore(appContext)

    fun load(): HubSettings {
        val generatedClientId = "main:android-${deviceSuffix()}"
        return HubSettings(
            baseUrl = preferences.getString(KEY_BASE_URL, HubSettings.DEFAULT_BASE_URL).orEmpty(),
            token = secrets.get(KEY_TOKEN).orEmpty(),
            username = preferences.getString(KEY_USERNAME, "admin").orEmpty(),
            clientId = preferences.getString(KEY_CLIENT_ID, generatedClientId).orEmpty(),
            deviceName = preferences.getString(KEY_DEVICE_NAME, "Android Main").orEmpty(),
            enabled = preferences.getBoolean(KEY_ENABLED, false),
        )
    }

    fun save(settings: HubSettings) {
        val baseUrl = HubSettings.normalizeBaseUrl(settings.baseUrl)
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) { "Hub 地址必须使用 http:// 或 https://" }
        require(settings.clientId.matches(Regex("[A-Za-z0-9:_-]{3,80}"))) { "Client ID 格式无效" }
        preferences.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_USERNAME, settings.username.trim().ifBlank { "admin" })
            .putString(KEY_CLIENT_ID, settings.clientId.trim())
            .putString(KEY_DEVICE_NAME, settings.deviceName.trim().ifBlank { "Android Main" })
            .putBoolean(KEY_ENABLED, settings.enabled)
            .apply()
        if (settings.token.isBlank()) secrets.remove(KEY_TOKEN) else secrets.put(KEY_TOKEN, settings.token.trim())
    }

    fun clearToken() = secrets.remove(KEY_TOKEN)

    private fun deviceSuffix(): String = runCatching {
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
            .takeLast(8)
            .lowercase(Locale.US)
    }.getOrDefault("device")

    private companion object {
        const val PREFERENCES = "hub_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_ENABLED = "enabled"
        const val KEY_TOKEN = "hub.channel_token"
    }
}
