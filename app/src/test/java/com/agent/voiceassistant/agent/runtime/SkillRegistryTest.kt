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

    @Test
    fun `lists and edits skill text files while preserving metadata`() {
        val runtime = Files.createTempDirectory("skill-editor").toFile()
        val root = runtime.resolve("skills").apply { mkdirs() }
        val skill = root.resolve("notes").apply { mkdirs() }
        skill.resolve("SKILL.md").writeText("---\nname: Notes\ndescription: Note flow\n---\nOriginal body")
        skill.resolve("references").mkdirs()
        skill.resolve("references/guide.md").writeText("Old guide")
        val modified = runtime.resolve("skills-user-modified")
        val registry = SkillRegistry(root, runtime.resolve("disabled"), runtime.resolve("deleted"), modified)

        assertEquals(listOf("SKILL.md", "references/guide.md"), registry.files("notes").map { it.relativePath })
        registry.updateFile("notes", "references/guide.md", "New guide")
        registry.updateMetadata("notes", "Updated Notes", "Updated description")

        assertEquals("New guide", skill.resolve("references/guide.md").readText())
        assertEquals("Updated Notes", registry.listAll().single().name)
        assertTrue(skill.resolve("SKILL.md").readText().contains("Original body"))
        assertTrue(modified.readText().contains("notes"))
    }

    @Test
    fun `skill editor rejects paths outside skill root`() {
        val runtime = Files.createTempDirectory("skill-editor-path").toFile()
        val root = runtime.resolve("skills").apply { mkdirs() }
        root.resolve("notes").apply { mkdirs() }.resolve("SKILL.md").writeText("# Notes")
        runtime.resolve("secret.txt").writeText("secret")
        val registry = SkillRegistry(root)

        assertThrows(IllegalArgumentException::class.java) {
            registry.readFile("notes", "../secret.txt")
        }
    }

    @Test
    fun `creates a standard editable single file skill`() {
        val runtime = Files.createTempDirectory("skill-create").toFile()
        val modified = runtime.resolve("skills-user-modified")
        val registry = SkillRegistry(
            runtime.resolve("skills"),
            runtime.resolve("disabled"),
            runtime.resolve("deleted"),
            modified,
        )

        val skill = registry.create("案件复盘", "按步骤复盘案件")

        assertEquals("案件复盘", skill.name)
        assertEquals(listOf("SKILL.md"), registry.files(skill.id).map { it.relativePath })
        assertTrue(registry.coreBody(skill.id).contains("适用场景"))
        assertTrue(modified.readText().contains(skill.id))
    }

    @Test
    fun `system skill is turn resident immutable and can only be disabled`() {
        val runtime = Files.createTempDirectory("system-skill").toFile()
        val systemRoot = runtime.resolve("system-skills").apply { mkdirs() }
        systemRoot.resolve("local-execution").apply { mkdirs() }.resolve("SKILL.md").writeText(
            "---\nname: 本地执行\ndescription: local tools\n---\nHidden protocol",
        )
        val disabled = runtime.resolve("system-disabled")
        val registry = SkillRegistry(
            runtime.resolve("skills"), runtime.resolve("disabled"), runtime.resolve("deleted"),
            runtime.resolve("modified"), systemRoot, disabled,
        )

        val skill = registry.list().single()
        assertTrue(skill.system)
        assertEquals(SkillRegistry.Residency.TURN, skill.residency)
        assertThrows(IllegalArgumentException::class.java) { registry.delete(skill.id) }
        assertThrows(IllegalArgumentException::class.java) {
            registry.updateMetadata(skill.id, "改名", "description")
        }
        registry.setEnabled(skill.id, false)
        assertTrue(registry.list().isEmpty())
        assertTrue(disabled.readText().contains(skill.id))
    }

    @Test
    fun `names are globally unique after unicode and whitespace normalization`() {
        val runtime = Files.createTempDirectory("skill-name").toFile()
        val registry = SkillRegistry(runtime.resolve("skills"))
        registry.create("案件  复盘", "first")

        assertThrows(IllegalArgumentException::class.java) {
            registry.create("案件 复盘", "duplicate")
        }
    }

    @Test
    fun `skill use loads one text resource and edit checks sha`() {
        val runtime = Files.createTempDirectory("skill-use").toFile()
        val registry = SkillRegistry(runtime.resolve("skills"))
        val skill = registry.create("资料整理", "整理资料", "# 流程")
        val core = registry.use("资料整理")

        assertEquals("SKILL.md", core.resourceName)
        assertEquals(SkillRegistry.Residency.CONVERSATION, core.skill.residency)
        val updated = registry.edit(
            skillName = "资料整理",
            operation = "replace_text",
            resourceName = "SKILL.md",
            expectedSha256 = core.resources.single().sha256,
            oldText = "# 流程",
            newText = "# 新流程",
        )
        assertTrue(registry.coreBody(updated.id).contains("新流程"))
    }
}
