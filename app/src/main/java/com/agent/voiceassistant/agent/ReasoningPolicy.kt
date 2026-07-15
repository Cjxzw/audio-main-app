package com.agent.voiceassistant.agent

object ReasoningPolicy {
    private val explicitDeepReasoningPhrases = listOf(
        "认真想",
        "仔细想",
        "深入想",
        "好好想",
        "深入分析",
        "深度分析",
        "深度思考",
        "仔细分析",
        "头脑风暴",
        "多想一会",
    )

    fun requestsDeepReasoning(userText: String): Boolean {
        val compact = userText.lowercase().replace(" ", "")
        return explicitDeepReasoningPhrases.any(compact::contains)
    }
}
