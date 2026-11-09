package com.agent.voiceassistant.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAssetSearchTest {
    @Test
    fun searchesAllFieldsWithoutCaseSensitivityOrMutation() {
        val fields = listOf("uid-123", "偏好 ABC 回复", "工作标签")
        assertTrue(ContextAssetSearch.matches(" UID-123 ", fields))
        assertTrue(ContextAssetSearch.matches("abc", fields))
        assertTrue(ContextAssetSearch.matches("标签", fields))
        assertTrue(ContextAssetSearch.matches("  ", fields))
        assertFalse(ContextAssetSearch.matches("AC", fields))
        assertFalse(ContextAssetSearch.matches("不存在", fields))
        assertTrue(fields == listOf("uid-123", "偏好 ABC 回复", "工作标签"))
    }
}
