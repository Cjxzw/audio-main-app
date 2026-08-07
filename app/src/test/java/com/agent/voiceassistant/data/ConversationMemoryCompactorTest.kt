package com.agent.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryCompactorTest {
    private val compactor = ConversationMemoryCompactor()

    @Test
    fun parsesStructuredMemoriesAndEvidence() {
        val result = compactor.parse(
            """```json
            {"memories":[{"content":"用户偏好简洁回答","category":"preference","tags":["沟通"],"evidence_message_ids":["m1"],"existing_memory_id":"old1"}]}
            ```""",
        )

        assertEquals(1, result.size)
        assertEquals("用户偏好简洁回答", result.single().content)
        assertEquals("old1", result.single().existingMemoryId)
        assertEquals(listOf("m1"), result.single().evidenceMessageIds)
    }

    @Test
    fun ignoresItemsWithoutEvidence() {
        val result = compactor.parse(
            """{"memories":[{"content":"没有依据","category":"profile","evidence_message_ids":[]}]}""",
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `strict parser accepts an explicit empty result`() {
        val result = compactor.parseStrict("""{"memories":[]}""")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `strict parser rejects malformed memory rather than treating it as empty`() {
        val result = compactor.parseStrict(
            """{"memories":[{"content":"没有证据","category":"profile","tags":[],"evidence_message_ids":[]}]}""",
        )

        assertTrue(result.isFailure)
    }
}
