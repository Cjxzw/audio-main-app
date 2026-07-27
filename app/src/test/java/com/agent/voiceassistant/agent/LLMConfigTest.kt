package com.agent.voiceassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LLMConfigTest {
    @Test
    fun systemPromptContainsOnlyStableInstructions() {
        val prompt = buildMainSystemPrompt()

        assertTrue(prompt.contains("喊我（Hanwo）"))
        assertTrue(prompt.contains("独立运行在 Android 手机上"))
        assertTrue(prompt.contains("绝不能要求用户必须连接外部设备"))
        assertFalse(prompt.contains("本回合思考策略"))
        assertFalse(prompt.contains("当前时间"))
        assertFalse(prompt.contains("当前是快速模式"))
        assertTrue(prompt.contains("agent_sleep"))
        assertTrue(prompt.contains("用户只是讨论、引用或询问这些词语时不得调用"))
        assertTrue(prompt.contains("<DETAILS>...</DETAILS>"))
        assertTrue(prompt.contains("普通 JSON、数组、Markdown 和代码围栏都是合法正文"))
        assertTrue(prompt.contains("复杂回复案例"))
        assertTrue(prompt.contains("不得在尚未搜索时直接回答“不知道”"))
        assertTrue(prompt.contains("资料可能冷门"))
        assertTrue(prompt.contains("只有用户意图或必要条件不明确时才简短追问"))
        assertTrue(prompt.contains("结果是否直接覆盖用户的核心问题"))
        assertTrue(prompt.contains("不得用相同关键词机械重试"))
        assertTrue(prompt.contains("从相反方向交叉验证"))
        assertTrue(prompt.contains("两个相互独立的可靠来源交叉验证"))
        assertTrue(prompt.contains("不得编造来源、数字或引用"))
        assertFalse(buildTurnGuidance().contains("不要使用 Markdown"))
        assertTrue(buildTurnGuidance().contains("搜索不依赖深度思考"))
        assertTrue(buildTurnGuidance().contains("结果不足、噪声大或互相冲突时"))
        assertTrue(buildTurnGuidance().contains("关键事实优先使用一手来源"))
    }

    @Test
    fun currentTurnContentKeepsGuidanceAndUserInputAtTheEnd() {
        val content = buildCurrentTurnUserContent(
            userText = "你好",
            timestamp = "2026-07-17 12:00:00 星期五 +08:00",
            source = "voice",
            network = "WiFi",
            recentUserTiming = "- 0 秒前收到一轮用户输入",
        )

        assertTrue(content.contains("当前网络：WiFi"))
        assertTrue(content.contains("request_deep_reasoning"))
        assertTrue(content.endsWith("<user_input>\n你好\n</user_input>"))
    }
}
