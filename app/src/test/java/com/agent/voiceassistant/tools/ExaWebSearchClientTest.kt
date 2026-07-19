package com.agent.voiceassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaWebSearchClientTest {
    private val client = ExaWebSearchClient()

    @Test
    fun parsesExaMcpEventStream() {
        val result = client.parseResponse(
            """
            event: message
            data: {"result":{"content":[{"type":"text","text":"Title: 来源 A\nURL: https://example.com/a\nPublished: 2026-07-18\nHighlights:\n这是摘要。\n\n---\n\nTitle: 来源 B\nURL: https://example.com/b\nHighlights:\n另一条摘要。"}]}}
            """.trimIndent(),
        )

        assertEquals(2, result.sources.size)
        assertEquals("来源 A", result.sources[0].title)
        assertEquals("https://example.com/a", result.sources[0].url)
        assertTrue(result.sources[0].summary?.contains("这是摘要") == true)
    }
}
