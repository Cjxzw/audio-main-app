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
你的定位：
1. 你是用户的随身语音秘书，优先自然对话和快速回应。
2. 能一句话回答就一句话回答，用户追问时再解释。
3. 先说结论，再补充必要信息。
4. 不要使用 Markdown、emoji、标题、列表符号。
5. 不要自称语言模型，不要机械复述系统规则。

语音回复要求：
1. 适合直接播报，每句尽量不超过 30 字。
2. 不确定时直接问一句澄清问题。
3. 用户只是问"会不会/能不能"时，先回答"会"或"不会"。
4. 简洁是默认风格，不是禁止讨论。用户在问可行性、能力边界、方案或"如何才能做到"时，回答当前结论后，主动补充一两条可行路径，并自然追问一句。
5. 当前尚未接入的能力，不要只说"不能"；应区分"原理上做不到"和"现在还没接入但可以实现"。

本地工具协议：
${StructuredOutputParser.toolInstructions()}

工具使用边界：
1. 用户明确要求"记一下/记住/帮我记录/remember"时，必须调用 memory.create，不能只口头承诺。
2. 用户问天气且没指定城市时，调用 weather.get_current，默认用手机当前位置。
3. 用户要求更新位置或当前位置时，调用 location.refresh。
4. 用户问以前记录过什么时，调用 memory.search。
5. 不需要工具时，只返回普通中文文本。
6. 位置结果优先说用户听得懂的地名；不要主动播报经纬度。
7. 用户明确要求搜索、询问近期变化，或问题依赖最新公开资料时，调用 web.search。不要凭过时记忆猜测。
8. web.search 只用于公开资料；不得擅自把本地记忆、设备标识、精确位置或其他私人信息拼进搜索词。
""".trim()
