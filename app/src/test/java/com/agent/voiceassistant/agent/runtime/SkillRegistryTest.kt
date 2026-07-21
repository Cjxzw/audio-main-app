package com.agent.voiceassistant.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test
    fun `disabled skill leaves active directory and reports unavailable`() {
        val runtime = Files.createTempDirectory("skills-runtime").toFile()
        val active = runtime.resolve("skills").apply { mkdirs() }
        val disabled = runtime.resolve("skills-disabled").apply { mkdirs() }
        active.resolve("notes").apply { mkdirs() }.resolve("SKILL.md").writeText(
            "---\nname: notes\ndescription: notes flow\n---\nRead notes.",
        )
        val registry = SkillRegistry(active, disabled, runtime.resolve("skills-deleted"))

        registry.setEnabled("notes", false)

        assertTrue(registry.list().isEmpty())
        assertTrue(disabled.resolve("notes/SKILL.md").isFile)
        assertTrue(registry.unavailableReason("/skills/notes/SKILL.md")!!.contains("已停用"))
    }

    @Test
    fun `registration moves compatible text skill out of workspace`() {
        val runtime = Files.createTempDirectory("skill-register").toFile()
        val workspace = runtime.resolve("workspace").apply { mkdirs() }
        val candidate = workspace.resolve("weather-guide").apply { mkdirs() }
        candidate.resolve("guide.md").writeText("# Weather guide\nUse the weather tool first.")
        val registry = SkillRegistry(
            runtime.resolve("skills"),
            runtime.resolve("skills-disabled"),
            runtime.resolve("skills-deleted"),
        )

        val result = registry.registerFromWorkspace(
            workspace,
            "/workspace/weather-guide",
            "weather guide",
            "天气查询流程",
            "guide.md",
            "已检查全部文件，不依赖脚本。",
            listOf("guide.md"),
        )

        assertTrue(!candidate.exists())
        assertTrue(runtime.resolve("skills/weather-guide/SKILL.md").isFile)
        assertEquals("weather guide", result.skill.name)
    }

    @Test
    fun `registration rejects script dependent skill`() {
        val runtime = Files.createTempDirectory("skill-script").toFile()
        val workspace = runtime.resolve("workspace").apply { mkdirs() }
        val candidate = workspace.resolve("scripted").apply { mkdirs() }
        candidate.resolve("SKILL.md").writeText("# Scripted")
        candidate.resolve("run.py").writeText("print('hello')")
        val registry = SkillRegistry(
            runtime.resolve("skills"),
            runtime.resolve("skills-disabled"),
            runtime.resolve("skills-deleted"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            registry.registerFromWorkspace(
                workspace,
                "/workspace/scripted",
                "scripted",
                "requires python",
                "SKILL.md",
                "依赖 Python。",
                listOf("SKILL.md", "run.py"),
            )
        }
    }
}
