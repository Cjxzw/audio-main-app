package com.agent.voiceassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VirtualPathResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `resolves a virtual source path`() {
        val source = temporaryFolder.newFolder("source")
        val target = source.resolve("app/build.gradle.kts").apply {
            parentFile?.mkdirs()
            writeText("plugins {}")
        }
        val resolver = resolver(source)

        assertEquals("/source/app/build.gradle.kts", resolver.normalize("/source/app/build.gradle.kts"))
        assertEquals(target.canonicalFile, resolver.resolve("/source/app/build.gradle.kts", write = false))
    }

    @Test
    fun `does not permit physical paths`() {
        val source = temporaryFolder.newFolder("source")
        val resolver = resolver(source)
        val outside = temporaryFolder.newFile("outside.txt")

        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(outside.absolutePath, write = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(source.absolutePath, write = false)
        }
    }

    @Test
    fun `keeps traversal blocked before physical mapping`() {
        val source = temporaryFolder.newFolder("source")
        val resolver = resolver(source)

        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve("/source/../outside.txt", write = false)
        }
    }

    private fun resolver(source: java.io.File) = VirtualPathResolver(
        sourceRoot = source,
        logsRoot = temporaryFolder.newFolder(),
        workspaceRoot = temporaryFolder.newFolder(),
    )
}
