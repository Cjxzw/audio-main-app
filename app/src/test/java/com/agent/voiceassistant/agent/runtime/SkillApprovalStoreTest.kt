package com.agent.voiceassistant.agent.runtime

import java.nio.file.Files
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class SkillApprovalStoreTest {
    private class Fixture {
        val root = Files.createTempDirectory("skill-approval").toFile()
        val workspace = root.resolve("workspace").apply { mkdirs() }
        val registry = SkillRegistry(root.resolve("skills"))
        val approvals = SkillApprovalStore(root, workspace, registry)
        fun create(name: String = "test") = buildJsonObject {
            put("skill_name", name); put("description", "test flow"); put("content", "# original")
        }
    }
    @Test fun `old pending policy must be resubmitted and can still be rejected`() {
        val f = Fixture()
        val request = f.approvals.propose("skill.create", f.create(), "origin")
        val state = f.root.resolve("skill-approval.json")
        state.writeText(state.readText().replace("\"manifestVersion\":2", "\"manifestVersion\":1").replace("\"reviewPolicyVersion\":2", "\"reviewPolicyVersion\":1"))
        val restarted = SkillApprovalStore(f.root, f.workspace, f.registry)
        assertThrows(IllegalArgumentException::class.java) { restarted.decide(request.id, "approved") }
        assertTrue(f.registry.list().isEmpty())
        restarted.decide(request.id, "rejected")
        assertNull(restarted.pending.value)
    }
    @Test fun `new skill is invisible until explicit approval`() {
        val f = Fixture()
        val request = f.approvals.propose("skill.create", f.create(), "origin")
        assertTrue(f.registry.list().isEmpty())
        f.approvals.decide(request.id, "approved")
        assertEquals("test", f.registry.list().single().name)
        assertNull(f.approvals.pending.value)
        assertEquals("origin", f.approvals.outcomes().single().conversationId)
    }
    @Test fun `deferred request persists globally blocks mutations and rejection frees slot`() {
        val f = Fixture()
        val request = f.approvals.propose("skill.create", f.create(), "origin")
        f.approvals.decide(request.id, "deferred")
        val restarted = SkillApprovalStore(f.root, f.workspace, f.registry)
        assertTrue(restarted.pending.value!!.deferred)
        assertThrows(IllegalStateException::class.java) { restarted.propose("skill.create", f.create("other"), "other") }
        restarted.decide(request.id, "rejected")
        assertTrue(f.registry.list().isEmpty())
        assertNotNull(restarted.propose("skill.create", f.create("other"), "other"))
    }
    @Test fun `candidate edits and installed version changes cannot be silently approved`() {
        val f = Fixture()
        val request = f.approvals.propose("skill.create", f.create(), "origin")
        java.io.File(request.candidate, "SKILL.md").appendText("\nchanged")
        assertThrows(IllegalArgumentException::class.java) { f.approvals.decide(request.id, "approved") }
        assertNotNull(f.approvals.pending.value)
        f.approvals.decide(request.id, "rejected")
        val skill = f.registry.create("editable", "flow", "# original")
        val loaded = f.registry.use(skill.name)
        val edit = buildJsonObject {
            put("skill_name", skill.name); put("operation", "replace_resource"); put("resource_name", "SKILL.md")
            put("expected_sha256", loaded.resources.single().sha256); put("new_text", "---\nname: editable\ndescription: flow\n---\n# candidate")
        }
        val change = f.approvals.propose("skill.edit", edit, "origin")
        assertTrue(f.registry.coreBody(skill.id).contains("original"))
        f.registry.updateFile(skill.id, "SKILL.md", "---\nname: editable\ndescription: flow\n---\n# manually changed")
        assertThrows(IllegalArgumentException::class.java) { f.approvals.decide(change.id, "approved") }
    }
    @Test fun `registered shell candidate can be read and run only after approval`() {
        val f = Fixture()
        val source = f.workspace.resolve("candidate").apply { mkdirs() }
        source.resolve("SKILL.md").writeText("# trusted shell")
        source.resolve("run.sh").writeText("printf ok")
        val payload = buildJsonObject {
            put("source_path", "/workspace/candidate"); put("name", "shell"); put("description", "trusted")
            put("core_file", "SKILL.md"); put("compatibility_notes", "Android sh")
            put("reviewed_files", buildJsonArray { add("SKILL.md"); add("run.sh") })
        }
        val request = f.approvals.propose("skill.register", payload, "origin")
        assertThrows(IllegalStateException::class.java) { f.registry.script("shell", "run.sh") }
        assertTrue(source.exists())
        f.approvals.decide(request.id, "approved")
        assertEquals("printf ok", f.registry.use("shell", "run.sh").content)
        assertEquals("run.sh", f.registry.script("shell", "run.sh").resourceName)
    }

    @Test fun `quickjs approval records runtime entry and permissions`() {
        val f = Fixture()
        val source = f.workspace.resolve("quick-flow").apply { mkdirs() }
        source.resolve("SKILL.md").writeText(
            "---\nname: quick flow\ndescription: quickjs\nruntime: quickjs\nentry: main.js\npermissions: read, sha256\n---\n# quick",
        )
        source.resolve("main.js").writeText("function main(input) { return input; }")
        val request = f.approvals.propose("skill.register", buildJsonObject {
            put("source_path", "/workspace/quick-flow"); put("name", "quick flow"); put("description", "quickjs")
            put("core_file", "SKILL.md"); put("reviewed_files", buildJsonArray { add("SKILL.md"); add("main.js") })
        }, "origin")

        assertEquals("quickjs", request.runtime)
        assertEquals("main.js", request.entry)
        assertEquals(setOf("read", "sha256"), request.permissions)
        assertEquals(request.digest, f.approvals.reviewRequest(request).candidateDigest)
    }
    @Test fun `concurrent proposals reserve only one slot`() {
        val f = Fixture()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { n -> executor.submit<Boolean> {
                runCatching { f.approvals.propose("skill.create", f.create("skill$n"), "conversation$n") }.isSuccess
            } }
            assertEquals(1, futures.count { it.get() })
        } finally { executor.shutdownNow() }
    }
    @Test fun `single markdown registration preserves original filename during review`() {
        val f = Fixture()
        val source = f.workspace.resolve("guide.md").apply { writeText("# single file") }
        val request = f.approvals.propose("skill.register", buildJsonObject {
            put("source_path", "/workspace/guide.md"); put("name", "single"); put("description", "flow")
            put("core_file", "guide.md"); put("reviewed_files", buildJsonArray { add("guide.md") })
        }, "origin")
        assertTrue(source.isFile)
        assertTrue(f.registry.list().isEmpty())
        f.approvals.decide(request.id, "approved")
        assertTrue(f.registry.use("single").content.contains("single file"))
    }
    @Test fun `whole package update publishes only on approval and preserves disabled state`() {
        val f = Fixture()
        val original = f.registry.create("server", "old", "# old")
        f.registry.setEnabled(original.id, false)
        val replacement = f.workspace.resolve("replacement").apply { mkdirs() }
        replacement.resolve("SKILL.md").writeText("# updated")
        replacement.resolve("run.sh").writeText("exit 0")
        val request = f.approvals.propose("skill.edit", buildJsonObject {
            put("skill_name", "server"); put("operation", "replace_from_workspace")
            put("expected_sha256", original.version); put("source_path", "/workspace/replacement")
        }, "origin")
        assertFalse(f.registry.files(original.id).any { it.relativePath == "run.sh" })
        f.approvals.decide(request.id, "approved")
        assertFalse(f.registry.listAll().single().enabled)
        assertTrue(f.registry.files(original.id).any { it.relativePath == "run.sh" })
        assertTrue(f.registry.coreBody(original.id).contains("updated"))
    }
    @Test fun `publishing journal recovers an already installed candidate`() {
        val f = Fixture()
        val request = f.approvals.propose("skill.create", f.create(), "origin")
        f.registry.publish(request.skillId, java.io.File(request.candidate), null)
        val stateFile = f.root.resolve("skill-approval.json")
        stateFile.writeText(stateFile.readText().replace("\"phase\":\"pending\"", "\"phase\":\"publishing\""))
        val restarted = SkillApprovalStore(f.root, f.workspace, f.registry)
        assertNull(restarted.pending.value)
        assertEquals("approved", restarted.outcomes().single().status)
    }
}
