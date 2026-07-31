package com.agent.voiceassistant.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class HubConnectionState {
    DISABLED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTH_FAILED,
    ERROR,
}

@Serializable
data class HubAgentFact(
    val agentId: String = "",
    val name: String = "",
    val kind: String = "",
    val online: Boolean = false,
    val status: String = "",
    val subId: String? = null,
    val projectId: String? = null,
    val projectName: String = "",
    val capabilities: List<String> = emptyList(),
    val model: String = "",
    val runtime: String = "",
    val version: String = "",
    val companion: Boolean = false,
    val dispatchable: Boolean? = null,
    val description: String = "",
    val updatedAt: String = "",
) {
    fun canDispatchFrom(clientId: String): Boolean {
        if (agentId.isBlank() || agentId == clientId || kind.equals("main", ignoreCase = true)) return false
        val serverAllowsDispatch = dispatchable
            ?: capabilities.any { it.equals("task", true) || it.equals("executor", true) }
        return serverAllowsDispatch && (online || status.equals("online", true) || status.equals("ready", true))
    }
}

@Serializable
data class HubTaskFact(
    val taskId: String = "",
    val title: String = "",
    val status: String = "created",
    val agentId: String = "",
    val summary: String = "",
    val failureReason: String? = null,
    val detailAvailable: Boolean = false,
    val attachments: List<String> = emptyList(),
    val updatedAt: String = "",
    val details: String = "",
)

@Serializable
data class HubFacts(
    val factsVersion: Long = 0,
    val eventId: String = "",
    val agents: List<HubAgentFact> = emptyList(),
    val tasks: List<HubTaskFact> = emptyList(),
)

internal enum class HubDeltaDecision {
    APPLY,
    IGNORE,
    REQUEST_SNAPSHOT,
}

internal fun decideHubDelta(currentVersion: Long, incomingVersion: Long): HubDeltaDecision = when {
    incomingVersion <= currentVersion -> HubDeltaDecision.IGNORE
    incomingVersion == currentVersion + 1 -> HubDeltaDecision.APPLY
    else -> HubDeltaDecision.REQUEST_SNAPSHOT
}

data class HubSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val token: String = "",
    val username: String = "admin",
    val clientId: String = "main:android",
    val deviceName: String = "Android Main",
    val enabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://jxzw.ltd:50080"

        fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')
    }
}
