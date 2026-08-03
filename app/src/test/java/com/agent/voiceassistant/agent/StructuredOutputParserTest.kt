package com.agent.voiceassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun `details boundary is display metadata not a tool request`() {
        val raw = "<DETAILS>\n## 详情\n```json\n{\"status\":\"ok\"}\n```\n</DETAILS>"

        assertFalse(StructuredOutputParser.containsToolProtocol(raw))
    }

    @Test
    fun `parses complete xml body tool call into normal arguments`() {
        val raw = """
            正在读取文件。
            <tool_call><function=read><parameter=path>/workspace/note.txt</parameter></function></tool_call>
        """.trimIndent()

        val calls = StructuredOutputParser.parseBodyToolCalls(raw)

        assertEquals(1, calls.size)
        assertEquals("read", calls.single().name)
        assertEquals("/workspace/note.txt", calls.single().arguments["path"]?.toString()?.trim('"'))
    }

    @Test
    fun `preserves structured json parameter values in xml`() {
        val raw = "<tool_call><function=exec><parameter=argv>[\"cp\",\"/source/a\",\"/workspace/a\"]</parameter></function></tool_call>"

        val call = StructuredOutputParser.parseBodyToolCalls(raw).single()

        assertEquals("[\"cp\",\"/source/a\",\"/workspace/a\"]", call.arguments["argv"].toString())
    }

    @Test
    fun `parses complete json body tool call`() {
        val raw = """{"name":"write","arguments":{"path":"/workspace/a.txt","content":"ok"}}"""

        val calls = StructuredOutputParser.parseBodyToolCalls(raw)

        assertEquals(1, calls.size)
        assertEquals("write", calls.single().name)
        assertEquals("/workspace/a.txt", calls.single().arguments["path"]?.toString()?.trim('"'))
    }

    @Test
    fun `does not parse tool example inside details`() {
        val raw = """
            <DETAILS>
            <tool_call><function=read><parameter=path>/workspace/example.txt</parameter></function></tool_call>
            </DETAILS>
        """.trimIndent()

        assertTrue(StructuredOutputParser.parseBodyToolCalls(raw).isEmpty())
    }

    @Test
    fun `stream gate suppresses preamble followed by body tool protocol`() {
        val gate = BodyToolCallStreamGate(probeChars = 160)

        assertTrue(gate.append("我来处理这个文件。 ").text.isEmpty())
        val detected = gate.append("<tool_call><function=read>")

        assertTrue(detected.protocolDetected)
        assertTrue(gate.finish().text.isEmpty())
    }

    @Test
    fun `stream gate stops oversized body protocol`() {
        val gate = BodyToolCallStreamGate(probeChars = 1)

        assertTrue(gate.append("<tool_call>").protocolDetected)
        assertTrue(gate.append("x".repeat(16 * 1024)).tooLarge)
    }
}
