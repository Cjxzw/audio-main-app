package com.agent.voiceassistant.reflection

import kotlinx.serialization.json.Json

object ReflectionValidator {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(content: String): Result<ReflectionAnalysis> = runCatching {
        require(content.isNotBlank()) { "反思正文为空" }
        require(!content.contains("```")) { "反思必须是纯 JSON" }
        val analysis = json.decodeFromString<ReflectionAnalysis>(content.trim())
        require(analysis.title.startsWith("[反思]")) { "title 必须以 [反思] 开头" }
        require(analysis.taskSummary.isNotBlank()) { "taskSummary 不能为空" }
        require(analysis.taskNature.isNotEmpty()) { "taskNature 不能为空" }
        require(analysis.complexity in setOf("low", "medium", "high")) { "complexity 无效" }
        require(
            analysis.delegationAssessment in setOf("prefer_delegate", "prefer_local", "uncertain"),
        ) { "delegationAssessment 无效" }
        require(analysis.whyDelegateOrNot.isNotBlank()) { "whyDelegateOrNot 不能为空" }
        require(analysis.whyNotDelegated.isNotBlank()) { "whyNotDelegated 不能为空" }
        require(analysis.lesson.isNotBlank()) { "lesson 不能为空" }
        require(analysis.confidence in 0.0..1.0) { "confidence 必须在 0 到 1 之间" }
        analysis
    }
}
