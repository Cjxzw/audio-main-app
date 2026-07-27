package com.agent.voiceassistant.tasks

object TaskReportPolicy {
    enum class Route { ACTIVE_AUDIO, DORMANT_EXTERNAL_AUDIO, NOTIFICATION, DEFER }

    fun route(
        dormant: Boolean,
        sameConversation: Boolean,
        externalOutputConnected: Boolean,
        userSpeaking: Boolean,
        audioReportsEnabled: Boolean = true,
    ): Route = when {
        !audioReportsEnabled -> Route.NOTIFICATION
        !sameConversation -> Route.NOTIFICATION
        !externalOutputConnected -> Route.NOTIFICATION
        dormant && externalOutputConnected -> Route.DORMANT_EXTERNAL_AUDIO
        userSpeaking -> Route.DEFER
        else -> Route.ACTIVE_AUDIO
    }

    fun styleInstructions(): String = """
        请把全部任务结果合并成不超过三句、自然且适合语音播报的中文总结。
        先说明任务是否完成，再用一句话概括对用户有用的结果；失败时只说明用户能理解的原因和下一步。
        播报正文不得出现代码函数名、类名、内部字段名、状态常量、文件路径、协议名或其他没有必要的专业术语，也不要复述执行步骤。
        即使原始结果很复杂，正文中的“结果”仍必须压缩成一句话；确实无法简化但需要留存的内容放入 <DETAILS>...</DETAILS>，内部可以使用 Markdown、表格和代码围栏。
        DETAILS 标签外必须自成完整结论。不要调用工具，不要解释内部机制。
    """.trimIndent()
}
