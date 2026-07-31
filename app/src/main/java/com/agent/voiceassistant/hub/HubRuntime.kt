package com.agent.voiceassistant.hub

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

object HubRuntime {
    private lateinit var client: HubClient
    private lateinit var config: HubConfigRepository
    private lateinit var authenticator: HubAuthenticator

    fun initialize(context: Context) {
        if (::client.isInitialized) return
        config = HubConfigRepository(context.applicationContext)
        client = HubClient(context.applicationContext)
        authenticator = HubAuthenticator()
        val settings = config.load()
        if (settings.enabled) client.connect(settings)
    }

    fun settings(): HubSettings = config.load()
    fun saveSettings(settings: HubSettings) {
        config.save(settings)
        if (settings.enabled) client.connect(settings) else client.disconnect()
    }
    suspend fun login(baseUrl: String, username: String, password: String) {
        val token = authenticator.login(baseUrl, username, password)
        saveSettings(
            settings().copy(
                baseUrl = HubSettings.normalizeBaseUrl(baseUrl),
                username = username.trim(),
                token = token,
                enabled = true,
            ),
        )
    }
    fun logout() {
        saveSettings(settings().copy(token = "", enabled = false))
    }
    fun state(): StateFlow<HubConnectionState> = client.state
    fun facts() = client.facts
    fun dispatchableAgents(facts: HubFacts = client.facts.value): List<HubAgentFact> {
        val clientId = settings().clientId
        return facts.agents.filter { it.canDispatchFrom(clientId) }
    }
    suspend fun refreshFacts(timeoutMs: Long = 1_500L) = client.refreshFacts(timeoutMs)
    suspend fun submitAction(actionType: String, payload: JsonObject, turnId: String, conversationId: String): HubActionResult =
        client.submitAction(actionType, payload, turnId, conversationId)
}
