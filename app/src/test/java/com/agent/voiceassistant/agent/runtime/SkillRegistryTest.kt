package com.agent.voiceassistant.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SkillRegistryTest {
    @Test
    fun `loads only skill index metadata`() {
        val root = Files.createTempDirectory("skills").toFile()
        val skillDir = root.resolve("gitea").apply { mkdirs() }
        skillDir.resolve("SKILL.md").writeText(
            """
            ---
            name: gitea
            description: 管理开发仓库中的 Issue
            ---
            # Detailed instructions
            Secret implementation details stay out of the prompt.
            """.trimIndent(),
        )

        val registry = SkillRegistry(root)

        assertEquals(1, registry.list().size)
        assertEquals("/skills/gitea/SKILL.md", registry.list().single().virtualPath)
        assertTrue(registry.promptSummary().contains("管理开发仓库中的 Issue"))
        assertTrue(!registry.promptSummary().contains("Secret implementation"))
    }
}
