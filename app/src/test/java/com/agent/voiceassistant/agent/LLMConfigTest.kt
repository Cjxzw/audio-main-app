package com.agent.voiceassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LLMConfigTest {
    @Test
    fun systemPromptContainsOnlyStableInstructions() {
        val prompt = buildMainSystemPrompt()

        assertTrue(prompt.contains("“喊我”（Hanwo）"))
        assertTrue(prompt.contains("轻量级私人主 Agent"))
        assertFalse(prompt.contains("本回合思考策略"))
        assertFalse(prompt.contains("当前时间"))
        assertFalse(prompt.contains("当前是快速模式"))
        assertTrue(prompt.contains("agent_sleep"))
        assertTrue(prompt.contains("用户只是讨论、引用或询问这些词语时不得调用"))
        assertTrue(prompt.contains("<DETAILS>...</DETAILS>"))
        assertTrue(prompt.contains("<device_context>"))
        assertTrue(prompt.contains("<multimodal_transcript>"))
        assertTrue(prompt.contains("hub_dispatch_task"))
        assertTrue(prompt.contains("subagent"))
        assertTrue(prompt.contains("复杂工作委派"))
    }

    @Test
    fun currentTurnContentKeepsGuidanceAndUserInputAtTheEnd() {
        val content = buildCurrentTurnUserContent(
            userText = "你好",
            timestamp = "2026-07-17 12:00:00 星期五 +08:00",
            source = "voice",
            network = "WiFi",
        )

        assertTrue(content.contains("当前网络：WiFi"))
        assertTrue(content.contains("request_deep_reasoning"))
        assertTrue(content.endsWith("<user_input>\n你好\n</user_input>"))
    }
}
