package com.agent.voiceassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class VirtualPathResolverTest {
    private val root = Files.createTempDirectory("agent-vfs").toFile()
    private val source = root.resolve("source").apply { mkdirs() }
    private val logs = root.resolve("logs").apply { mkdirs() }
    private val workspace = root.resolve("workspace").apply { mkdirs() }
    private val skills = root.resolve("skills").apply { mkdirs() }
    private val resolver = VirtualPathResolver(source, logs, workspace, skills)

    @Test
    fun `maps virtual workspace path into physical root`() {
        assertEquals(
            workspace.resolve("notes/test.md").canonicalFile,
            resolver.resolve("/workspace/notes/test.md", write = true),
        )
    }

    @Test
    fun `normalizes repeated separators`() {
        assertEquals("/logs/voice-agent.log", resolver.normalize("//logs///voice-agent.log/"))
    }

    @Test
    fun `rejects traversal and relative paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve("/workspace/../source/secret.kt", write = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve("workspace/file.txt", write = false)
        }
    }

    @Test
    fun `only workspace is writable`() {
        listOf("/source/a.kt", "/logs/app.log", "/skills/x/SKILL.md").forEach { path ->
            assertThrows(IllegalStateException::class.java) { resolver.resolve(path, write = true) }
        }
    }
}
