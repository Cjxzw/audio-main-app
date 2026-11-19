package com.agent.voiceassistant.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCommandRegistryPolicyTest {
    @Test
    fun httpIsAvailableToSkillsButNotOneShotRun() {
        assertTrue("http_request" in RuntimeCommandRegistry.COMMANDS)
        assertFalse("http_request" in RuntimeCommandRegistry.RUN_COMMANDS)
        assertEquals(
            setOf("list", "find", "grep", "stat", "wc", "sha256"),
            RuntimeCommandRegistry.RUN_COMMANDS,
        )
    }

    @Test
    fun auditedFileCommandsAreNotAvailableThroughRun() {
        assertTrue(setOf("read", "write", "delete", "zip", "unzip").all { it in RuntimeCommandRegistry.COMMANDS })
        assertFalse(RuntimeCommandRegistry.RUN_COMMANDS.any { it in setOf("read", "write", "delete", "zip", "unzip") })
    }

    @Test
    fun commandNamesAreFlatAndDoNotExposeLegacyNamespaces() {
        assertTrue(setOf("list", "find", "grep", "sha256").all { it in RuntimeCommandRegistry.COMMANDS })
        assertFalse(RuntimeCommandRegistry.COMMANDS.any { it.contains('.') })
        assertFalse("workspace.list" in RuntimeCommandRegistry.COMMANDS)
        assertFalse("workspace.find" in RuntimeCommandRegistry.COMMANDS)
        assertFalse("workspace.delete" in RuntimeCommandRegistry.COMMANDS)
        assertFalse("http.request" in RuntimeCommandRegistry.COMMANDS)
        assertFalse("crypto.sha256" in RuntimeCommandRegistry.COMMANDS)
    }
}
