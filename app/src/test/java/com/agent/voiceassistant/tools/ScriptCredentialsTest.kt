package com.agent.voiceassistant.tools

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class ScriptCredentialsTest {
    @Test fun `credential expands as a literal value in all shell word quoting contexts`() {
        val shell = listOf(File("/bin/sh"), File("C:/Program Files/Git/bin/bash.exe")).firstOrNull { it.isFile }
        assumeNotNull(shell)
        val token = "{{cred:test.field}}"
        val secret = "a'b\"c\$HOME\n`printf WRONG` ; : /workspace unchanged"
        val source = "# don't expand $token\nprintf '%s\\n' '$token' \"$token\" $token"
        val expansion = ScriptCredentials.expand(source) { secret }
        val script = kotlin.io.path.createTempFile("hanwo-shell-test", ".sh").toFile().apply { writeText(expansion.source) }
        val process = ProcessBuilder(shell!!.absolutePath, script.absolutePath.replace('\\', '/'))
            .also { it.environment().putAll(expansion.environment) }.start()
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        assertEquals(errors, 0, process.waitFor())
        assertEquals(listOf(secret, secret, secret).joinToString("\n", postfix = "\n"), output)
        assertEquals(1, expansion.environment.size)
        script.delete()
    }
    @Test fun `missing credentials fail before execution and escaped tokens are literal`() {
        assertThrows(IllegalStateException::class.java) { ScriptCredentials.expand("echo '{{cred:missing.value}}'") { error("expired") } }
        assertTrue(ScriptCredentials.expand("# {{cred:example.value}}") { error("must not resolve comments") }.environment.isEmpty())
    }
}
