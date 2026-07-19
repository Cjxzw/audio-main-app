package com.agent.voiceassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredOutputParserTest {
    @Test
    fun `detects pseudo xml tool call`() {
        val raw = """
            <tool_call>
            <function=exec><parameter=command>pwd</parameter></function>
            </tool_call>
        """.trimIndent()

        assertTrue(StructuredOutputParser.containsStructuredProtocol(raw))
        assertTrue(StructuredOutputParser.containsToolProtocol(raw))
    }

    @Test
    fun `detects tool json but not ordinary json`() {
        assertTrue(
            StructuredOutputParser.containsToolProtocol(
                """{"name":"exec","arguments":{"command":"pwd"}}""",
            ),
        )
        assertFalse(
            StructuredOutputParser.containsToolProtocol(
                """{"status":"ok","message":"普通数据"}""",
            ),
        )
    }

    @Test
    fun `reply envelope is structured but not a tool request`() {
        val raw = "<REPLY>正常回复</REPLY>"

        assertTrue(StructuredOutputParser.containsStructuredProtocol(raw))
        assertFalse(StructuredOutputParser.containsToolProtocol(raw))
    }

    @Test
    fun `xml inside markdown fence is display detail not tool request`() {
        val raw = """
            这是接口示例：
            ```xml
            <tool_call><function=exec></function></tool_call>
            ```
        """.trimIndent()

        assertFalse(StructuredOutputParser.containsToolProtocol(raw))
    }
}
