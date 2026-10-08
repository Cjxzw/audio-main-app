package com.agent.voiceassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LLMConfigTest {
    @Test
    fun systemPromptContainsOnlyStableInstructions() {
        val prompt = buildMainSystemPrompt()

        assertTrue(prompt.contains("“喊我”（Hanwo）"))
        assertTrue(prompt.contains("轻量级私人 Main Agent"))
        assertFalse(prompt.contains("本回合思考策略"))
        assertFalse(prompt.contains("当前时间"))
        assertFalse(prompt.contains("当前是快速模式"))
        assertTrue(prompt.contains("agent_sleep"))
        assertFalse(prompt.contains("回复时的原则"))
        assertFalse(prompt.contains("<DETAILS>...</DETAILS>"))
        assertFalse(prompt.contains("准确的分辨出用户的意图"))
        assertTrue(prompt.contains("<device_context>"))
        assertTrue(prompt.contains("<multimodal_transcript>"))
        assertTrue(prompt.contains("hub_dispatch_task"))
        assertTrue(prompt.contains("subagent"))
        assertTrue(prompt.contains("Main、Hub 与执行 Agent"))
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
        assertFalse(content.contains("request_deep_reasoning"))
        assertTrue(content.endsWith("<user_input>\n你好\n</user_input>"))
    }

    @Test
    fun realtimePromptIsIndependentFromMainAgentInstructions() {
        val prompt = buildRealtimeSystemPrompt()

        assertTrue(prompt.contains("实时语音助手"))
        assertTrue(prompt.contains("转交主会话"))
        assertTrue(prompt.contains("结束实时通话"))
        assertFalse(prompt.contains("skill_use"))
        assertFalse(prompt.contains("hub_dispatch_task"))
        assertFalse(prompt.contains("agent_sleep"))
        assertFalse(prompt.contains("长期记忆，调用 memory_create"))
    }
}
