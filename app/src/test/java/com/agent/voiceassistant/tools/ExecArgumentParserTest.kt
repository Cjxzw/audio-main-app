package com.agent.voiceassistant.tools

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExecArgumentParserTest {
    private val json = Json

    @Test
    fun acceptsNativeArray() {
        assertEquals(
            listOf("cp", "/source/a", "/workspace/a"),
            ExecArgumentParser.parse(json.parseToJsonElement("[\"cp\",\"/source/a\",\"/workspace/a\"]")),
        )
    }

    @Test
    fun acceptsOneStringificationLayer() {
        assertEquals(
            listOf("cp", "/source/a", "/workspace/a"),
            ExecArgumentParser.parse(json.parseToJsonElement("\"[\\\"cp\\\",\\\"/source/a\\\",\\\"/workspace/a\\\"]\"")),
        )
    }

    @Test
    fun rejectsShellCommandString() {
        assertThrows(IllegalArgumentException::class.java) {
            ExecArgumentParser.parse(json.parseToJsonElement("\"cat /workspace/a | head\""))
        }
    }
}
