package com.agent.voiceassistant.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDeletePolicyTest {
    @Test
    fun blocksShellDeletionCommands() {
        assertTrue(WorkspaceDeletePolicy.attemptsDirectDeletion("rm -rf report"))
        assertTrue(WorkspaceDeletePolicy.attemptsDirectDeletion("find . -name '*.tmp' -delete"))
        assertTrue(WorkspaceDeletePolicy.attemptsDirectDeletion("echo ok; unlink old.txt"))
        assertTrue(WorkspaceDeletePolicy.attemptsDirectDeletion("/system/bin/rm old.txt"))
    }

    @Test
    fun allowsOrdinaryTextProcessing() {
        assertFalse(WorkspaceDeletePolicy.attemptsDirectDeletion("sed -n '1,20p' report.txt"))
        assertFalse(WorkspaceDeletePolicy.attemptsDirectDeletion("echo remove duplicates"))
    }
}
