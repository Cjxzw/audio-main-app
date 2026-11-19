package com.agent.voiceassistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillScriptInstrumentedTest {
    @Test fun virtualPathsNestedShellCredentialsAndFailureWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = AndroidExecutionEnv(context)
        val testRoot = File(context.cacheDir, "skill-test-${UUID.randomUUID()}")
        val registry = SkillRegistry(File(testRoot, "skills"))
        val skill = registry.create("shell-test", "test", "# test")
        val root = File(testRoot, "skills/${skill.id}")
        val outputName = "script-test-${UUID.randomUUID()}.txt"
        val credential = VaultCredential(name = "script test", sessionId = "test", createdAt = System.currentTimeMillis(),
            allowedUrls = listOf("https://unused.invalid"), fields = listOf(VaultField(name = "token", type = "key", value = "a'b\"\$literal")))
        val vault = CredentialVault(context)
        vault.save(credential)
        val reference = CredentialPolicy.token(credential, credential.fields.single())
        try {
            File(root, "child.sh").writeText("printf '%s' '$reference' > /workspace/$outputName")
            File(root, "run.sh").writeText("""
                set -eu
                prefix=/work
                suffix=space
                target="${'$'}prefix${'$'}suffix/$outputName"
                sh ./child.sh
                test -f "${'$'}target"
                touch "${'$'}target"
                cat "${'$'}target"
                printf '\n/workspace stays literal'
            """.trimIndent())
            val success = env.runSkillScript(registry.script(skill.name, "run.sh"), timeoutSeconds = 5)
            assertEquals(success.output, 0, success.exitCode)
            assertTrue(success.output.contains(credential.fields.single().value))
            assertTrue(success.output.contains("/workspace stays literal"))
            File(root, "fail.sh").writeText("printf 'request2 failed'; exit 42\nprintf 'must not run'")
            val failure = env.runSkillScript(registry.script(skill.name, "fail.sh"), timeoutSeconds = 5)
            assertEquals(42, failure.exitCode)
            assertFalse(failure.output.contains("must not run"))
            val earlyExit = env.runSkillScript(registry.script(skill.name, "fail.sh"), input = "x".repeat(1024 * 1024), timeoutSeconds = 5)
            assertEquals(earlyExit.output, 42, earlyExit.exitCode)
            File(root, "timeout.sh").writeText("printf 'before timeout'; exec sleep 5")
            val timeout = env.runSkillScript(registry.script(skill.name, "timeout.sh"), timeoutSeconds = 1)
            assertTrue(timeout.timedOut)
            assertNull(timeout.exitCode)
            assertEquals("before timeout", timeout.output)
        } finally {
            vault.delete(credential.id)
            File(env.workspaceRoot, outputName).delete()
            testRoot.deleteRecursively()
        }
    }
}
