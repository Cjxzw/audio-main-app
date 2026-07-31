package com.agent.voiceassistant.hub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubProtocolModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `agent facts decode camel case route fields`() {
        val fact = json.decodeFromString<HubAgentFact>(
            """{"agentId":"edge:mimocode-ubuntu","name":"MiMoCode","kind":"executor","online":true,"subId":"sub_ubuntu","capabilities":["task","code"],"model":"MiMoCode","version":"0.1.9","companion":false,"dispatchable":true}""",
        )

        assertEquals("edge:mimocode-ubuntu", fact.agentId)
        assertEquals("sub_ubuntu", fact.subId)
        assertTrue(fact.online)
        assertEquals(listOf("task", "code"), fact.capabilities)
        assertEquals("MiMoCode", fact.model)
        assertEquals("0.1.9", fact.version)
        assertTrue(fact.canDispatchFrom("main:android"))
    }

    @Test
    fun `dispatchable routes exclude current and other main agents`() {
        val current = HubAgentFact(
            agentId = "main:android",
            kind = "main",
            online = true,
            capabilities = listOf("task"),
            dispatchable = true,
        )
        val legacyMain = current.copy(agentId = "main:secretary")
        val offlineExecutor = HubAgentFact(
            agentId = "edge:offline",
            kind = "executor",
            capabilities = listOf("task"),
            dispatchable = true,
        )

        assertFalse(current.canDispatchFrom("main:android"))
        assertFalse(legacyMain.canDispatchFrom("main:android"))
        assertFalse(offlineExecutor.canDispatchFrom("main:android"))
    }

    @Test
    fun `task facts default unknown optional fields`() {
        val fact = json.decodeFromString<HubTaskFact>("""{"taskId":"task_1","status":"completed"}""")

        assertEquals("task_1", fact.taskId)
        assertEquals("completed", fact.status)
        assertEquals("", fact.summary)
        assertFalse(fact.detailAvailable)
        assertTrue(fact.attachments.isEmpty())
    }

    @Test
    fun `action result preserves structured error`() {
        val message = buildJsonObject {
            put("type", "action.result")
            put("requestId", "req_1")
            put("ok", false)
            putJsonObject("error") {
                put("code", "target_offline")
                put("message", "目标执行 Agent 当前不在线。")
            }
        }

        val result = HubActionResult.from(message, json)

        assertFalse(result.ok)
        assertEquals("req_1", result.requestId)
        assertEquals("target_offline", result.errorCode)
        assertEquals("目标执行 Agent 当前不在线。", result.errorMessage)
    }

    @Test
    fun `hub settings keep public default endpoint`() {
        val settings = HubSettings()

        assertEquals("http://jxzw.ltd:50080", settings.baseUrl)
        assertFalse(settings.enabled)
        assertTrue(settings.token.isBlank())
    }

    @Test
    fun `websocket url converts final http url to ws`() {
        val url = buildHubWebSocketUrl(
            HubSettings(
                baseUrl = "http://example.test:50080/",
                token = "t",
                clientId = "main:test",
                deviceName = "Test",
            ),
        )

        assertEquals(
            "ws://example.test:50080/ws/main?token=t&client_id=main%3Atest&name=Test&device_id=main%3Atest",
            url,
        )
    }

    @Test
    fun `facts delta applies only the next version`() {
        assertEquals(HubDeltaDecision.IGNORE, decideHubDelta(7, 7))
        assertEquals(HubDeltaDecision.IGNORE, decideHubDelta(7, 6))
        assertEquals(HubDeltaDecision.APPLY, decideHubDelta(7, 8))
        assertEquals(HubDeltaDecision.REQUEST_SNAPSHOT, decideHubDelta(7, 9))
    }
}
