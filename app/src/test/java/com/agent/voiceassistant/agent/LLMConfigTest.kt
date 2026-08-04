package com.agent.voiceassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LLMConfigTest {
    @Test
    fun systemPromptContainsOnlyStableInstructions() {
        val prompt = buildMainSystemPrompt()

        assertTrue(prompt.contains("Hanwo"))
        assertTrue(prompt.contains("运行在用户手机侧"))
        assertFalse(prompt.contains("独立运行在 Android 手机上"))
        assertFalse(prompt.contains("本回合思考策略"))
        assertFalse(prompt.contains("当前时间"))
        assertFalse(prompt.contains("当前是快速模式"))
        assertTrue(prompt.contains("agent_sleep"))
        assertTrue(prompt.contains("<DETAILS>...</DETAILS>"))
        assertTrue(prompt.contains("<device_context>"))
        assertTrue(prompt.contains("<multimodal_transcript>"))
        assertTrue(prompt.contains("skill_use"))
        assertTrue(prompt.contains("Skill 目录不属于通用文件系统"))
        assertFalse(prompt.contains("本回合引导词"))
    }

    @Test
    fun currentTurnContentKeepsRuntimeFactsAndUserInputAtTheEnd() {
        val content = buildCurrentTurnUserContent(
            userText = "你好",
            timestamp = "2026-07-17 12:00:00 星期五 +08:00",
            source = "voice",
            network = "WiFi",
        )

        assertTrue(content.contains("当前网络：WiFi"))
        assertFalse(content.contains("近期用户输入时间间隔"))
        assertFalse(content.contains("本回合引导词"))
        assertTrue(content.endsWith("<user_input>\n你好\n</user_input>"))
    }
}
