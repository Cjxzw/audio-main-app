package com.agent.voiceassistant.agent

import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.UserMessage
import dev.langchain4j.service.V

/**
 * Assistant：LangChain4j AiServices 接口。
 *
 * LangChain4j 在运行时生成实现类，方法签名约定：
 * - `chat(text)` 普通对话
 * - `inject(prompt)` 注入系统级文本（用于汇报注入）
 * - `clearContext()` 清空对话历史
 *
 * `@SystemMessage` 由 [AgentFactory] 在构建时覆盖。
 */
interface Assistant {

    /**
     * 普通对话：用户语音识别后的文本。
     */
    @SystemMessage("placeholder")  // 由 AgentFactory 覆盖
    fun chat(@UserMessage text: String): String

    /**
     * 系统级注入：例如汇报策略层注入后台任务完成通知。
     * @param prompt 完整的注入提示词（已包含 [SystemMessage] 角色）
     */
    fun inject(@V("prompt") prompt: String): String

    /**
     * 清空对话上下文（重启会话）。
     */
    fun clearContext()
}
