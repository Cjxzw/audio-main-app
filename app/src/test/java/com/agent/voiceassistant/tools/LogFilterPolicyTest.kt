package com.agent.voiceassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterPolicyTest {
    private val lines = listOf(
        "10:00:00.000 I/VA_AUDIO: audio.route.ready | device=headset",
        "10:00:01.000 W/VA_TTS: tts.stream.timeout | elapsedMs=15000",
        "java.io.IOException: timeout",
        "    at example.Tts.run(Tts.kt:10)",
        "10:00:02.000 I/VA_AGENT: agent.tool.finished | name=read",
    )

    @Test
    fun `filters by short tag while preserving multiline entries`() {
        val filtered = LogFilterPolicy.filter(lines, emptyList(), listOf("TTS"), emptyList(), null)

        assertEquals(3, filtered.size)
        assertTrue(filtered.first().contains("VA_TTS"))
        assertTrue(filtered.last().contains("Tts.kt"))
    }

    @Test
    fun `combines level event prefix and query filters`() {
        val filtered = LogFilterPolicy.filter(
            lines = lines,
            levels = listOf("I"),
            tags = emptyList(),
            eventPrefixes = listOf("agent.tool"),
            query = "name=read",
        )

        assertEquals(listOf(lines.last()), filtered)
    }
}
