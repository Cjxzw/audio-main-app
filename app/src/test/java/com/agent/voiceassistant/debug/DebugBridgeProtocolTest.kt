package com.agent.voiceassistant.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBridgeProtocolTest {
    @Test
    fun `decodes supported request`() {
        val request = DebugBridgeProtocol.decodeRequest(
            """
            {
              "version": 1,
              "request_id": "0123456789abcdef",
              "command": "provider.set",
              "arguments": {"model": "mimo-v2.5-pro"}
            }
            """.trimIndent(),
        )

        assertEquals("0123456789abcdef", request.request_id)
        assertEquals("provider.set", request.command)
        assertEquals("mimo-v2.5-pro", request.arguments["model"].toString().trim('"'))
    }

    @Test
    fun `rejects unsafe request id and unknown fields`() {
        val unsafeId = runCatching {
            DebugBridgeProtocol.decodeRequest(
                """{"request_id":"../../prefs","command":"status"}""",
            )
        }
        val unknownField = runCatching {
            DebugBridgeProtocol.decodeRequest(
                """{"request_id":"0123456789abcdef","command":"status","secret":"value"}""",
            )
        }

        assertTrue(unsafeId.isFailure)
        assertTrue(unknownField.isFailure)
    }
}
