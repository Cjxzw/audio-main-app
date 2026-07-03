package com.agent.voiceassistant.agent

import com.agent.voiceassistant.BuildConfig

/**
 * LLM 供应商配置。
 * 默认走 .env 注入的 LLM_API_KEY / LLM_BASE_URL / LLM_MODEL（OpenAI 兼容格式）。
 * STEPFUN_* 与 OPENAI_* 字段共享同一份 .env 配置，保留两个名字仅为向后兼容。
 */
data class LLMConfig(
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024,
    /** 请求超时秒 */
    val timeoutSeconds: Long = 60
) {
    companion object {
        private val PLACEHOLDERS = setOf("", "YOUR_KEY_HERE", "YOUR_STEPFUN_KEY_HERE", "YOUR_OPENAI_KEY_HERE")

        /** 主配置：从 .env 注入（默认小米 MiMo） */
        fun stepFun(): LLMConfig = LLMConfig(
            apiKey = BuildConfig.STEPFUN_API_KEY,
            baseUrl = BuildConfig.STEPFUN_BASE_URL,
            modelName = BuildConfig.STEPFUN_MODEL
        )

        /** 备选 OpenAI（与主配置共享同一份 .env） */
        fun openAI(): LLMConfig = LLMConfig(
            apiKey = BuildConfig.OPENAI_API_KEY,
            baseUrl = BuildConfig.OPENAI_BASE_URL,
            modelName = BuildConfig.OPENAI_MODEL
        )

        /** 自动选择：检查 .env 是否已填写真实 key */
        fun auto(): LLMConfig {
            val cfg = stepFun()
            if (cfg.apiKey !in PLACEHOLDERS) return cfg
            return openAI()
        }
    }
}

/**
 * 系统提示词。
 * 强调简洁中文回复（语音场景应避免长文本）。
 */
val DEFAULT_SYSTEM_PROMPT = """
你是一个中文语音助手，名字叫小助。
回答要求：
1. 用简洁的口语化中文回答（适合语音播报，每句不超过 30 字）
2. 不要使用 Markdown 格式（**加粗**、# 标题等）
3. 不要使用 emoji 或特殊符号
4. 收到任务委派工具调用后，简短确认即可（如"已安排"），不要复述任务细节
5. 如果用户的问题不清楚，简短询问澄清
6. 工具调用结果由你转述给用户，不要说"工具返回"
""".trim()
