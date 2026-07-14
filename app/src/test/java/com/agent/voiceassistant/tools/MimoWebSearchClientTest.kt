package com.agent.voiceassistant.tools

import com.agent.voiceassistant.agent.LLMConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimoWebSearchClientTest {
    private val client = MimoWebSearchClient(
        LLMConfig(apiKey = "test", baseUrl = "https://example.com/v1", modelName = "mimo-v2.5"),
    )

    @Test
    fun parsesAnswerSourcesAndUsage() {
        val result = client.parseResponse(
            """
            {
              "choices": [{
                "message": {
                  "content": "搜索后的答案",
                  "annotations": [
                    {"type":"url_citation","url":"https://example.com/a","title":"来源 A","summary":"摘要 A","site_name":"示例","publish_time":"2026-07-14"},
                    {"type":"url_citation","url":"https://example.com/a","title":"重复来源"},
                    {"type":"url_citation","url":"javascript:alert(1)","title":"无效来源"}
                  ]
                }
              }],
              "usage": {"web_search_usage":{"tool_usage":3,"page_usage":15}}
            }
            """.trimIndent(),
        )

        assertEquals("搜索后的答案", result.answer)
        assertEquals(1, result.sources.size)
        assertEquals("来源 A", result.sources.single().title)
        assertEquals(3, result.toolUsage)
        assertEquals(15, result.pageUsage)
    }

    @Test
    fun acceptsSourcesWithoutGeneratedAnswer() {
        val result = client.parseResponse(
            """{"choices":[{"message":{"content":"","annotations":[{"url":"https://example.com"}]}}]}""",
        )

        assertTrue(result.answer.isEmpty())
        assertEquals("https://example.com", result.sources.single().url)
    }
}
