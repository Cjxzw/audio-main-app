package com.agent.voiceassistant.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceLinkPolicyTest {
    @Test
    fun `resolves absolute virtual workspace links and decodes unicode`() {
        assertEquals("报告/长 文本.md", WorkspaceLinkPolicy.relativePath("/workspace/%E6%8A%A5%E5%91%8A/%E9%95%BF%20%E6%96%87%E6%9C%AC.md"))
        assertEquals("报告.md", WorkspaceLinkPolicy.relativePath("hanwo://workspace/%E6%8A%A5%E5%91%8A.md"))
        assertEquals("a+b.md", WorkspaceLinkPolicy.relativePath("/workspace/a+b.md"))
        assertEquals("docs/guide.md", WorkspaceLinkPolicy.relativePath("https://appassets.androidplatform.net/workspace/docs/guide.md"))
    }

    @Test
    fun `resolves relative links from a workspace markdown file`() {
        assertEquals("docs/guide.md", WorkspaceLinkPolicy.relativePath("guide.md", "docs/readme.md"))
        assertEquals("guide.md", WorkspaceLinkPolicy.relativePath("../guide.md", "docs/readme.md"))
    }

    @Test
    fun `does not intercept external links or escape workspace`() {
        assertNull(WorkspaceLinkPolicy.relativePath("https://example.com/workspace/file.md"))
        assertNull(WorkspaceLinkPolicy.relativePath("/workspace/../../secret.txt"))
    }
}
