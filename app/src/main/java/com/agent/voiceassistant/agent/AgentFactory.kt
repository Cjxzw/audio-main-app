package com.agent.voiceassistant.agent

import com.agent.voiceassistant.report.PendingResultReporter
import com.agent.voiceassistant.service.TaskDispatcher
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.service.AiServices
import timber.log.Timber
import java.time.Duration

/**
 * AgentFactory：构建 LangChain4j [Assistant] 实例。
 *
 * 设计：
 * - 使用 [MessageWindowChatMemory] 管理上下文（保留最近 20 条消息）
 * - 通过 [OpenAiChatModel] 接 StepFun（OpenAI 兼容 API）
 * - 工具对象通过 [AgentTools] 注入，包含天气/时间/任务委派等
 *
 * Android 兼容性注意：
 * - 只用核心模块（chatModel + tools + chatMemory）
 * - 避开 langchain4j-agentic 等实验性模块（反射密集，可能崩溃）
 */
class AgentFactory(
    private val config: LLMConfig = LLMConfig.auto(),
    private val taskDispatcher: TaskDispatcher
) {

    private var assistant: Assistant? = null
    private var tools: AgentTools? = null

    /**
     * 创建（或返回缓存的）Assistant 实例。
     */
    fun create(): Assistant {
        assistant?.let { return it }

        Timber.i("Building Assistant with model=${config.modelName} base=${config.baseUrl}")

        val chatModel = OpenAiChatModel.builder()
            .baseUrl(config.baseUrl)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
            .maxTokens(config.maxTokens)
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .logRequests(true)
            .logResponses(true)
            .build()

        val chatMemory = MessageWindowChatMemory.withMaxMessages(20)

        val agentTools = AgentTools(taskDispatcher)
        tools = agentTools

        val instance = AiServices.builder(Assistant::class.java)
            .chatLanguageModel(chatModel)
            .chatMemory(chatMemory)
            .tools(agentTools)
            .systemMessageProvider { buildMainSystemPrompt() }
            .build()

        assistant = instance
        Timber.i("Assistant built successfully")
        return instance
    }

    /** 暴露给汇报策略层使用（注入后台任务结果时调用 assistant.inject） */
    fun tools(): AgentTools? = tools

    /** 重建 Assistant（清空上下文） */
    fun rebuild(): Assistant {
        assistant = null
        return create()
    }
}
