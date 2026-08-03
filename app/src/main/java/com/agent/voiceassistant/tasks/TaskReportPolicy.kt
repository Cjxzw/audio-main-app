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

    fun summaryInstructions(): String = """
        请把全部任务结果合并成不超过三句、自然且适合语音播报的中文总结。
        先说明任务是否完成，再用一句话概括对用户有用的结果；失败时只说明用户能理解的原因和下一步。
        播报正文不得出现代码函数名、类名、内部字段名、状态常量、文件路径、协议名或其他没有必要的专业术语，也不要复述执行步骤。
        即使原始结果很复杂，也必须压缩成简短结论。只输出摘要，不要输出 DETAILS 标签、原始正文或前后说明。
    """.trimIndent()

    fun normalizeSummary(candidate: String, fallback: String): String {
        val withoutDetails = candidate
            .replace(Regex("<DETAILS?>.*?</DETAILS?>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("</?DETAILS?>", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { fallback.trim() }
        var sentenceCount = 0
        var end = withoutDetails.length
        withoutDetails.forEachIndexed { index, char ->
            if (char in SUMMARY_SENTENCE_ENDINGS) {
                sentenceCount += 1
                if (sentenceCount == MAX_SUMMARY_SENTENCES) {
                    end = index + 1
                    return@forEachIndexed
                }
            }
        }
        return withoutDetails.take(end).trim()
    }

    fun composeChatReport(summary: String, tasks: List<TaskEntity>): String {
        require(tasks.isNotEmpty())
        val details = if (tasks.size == 1) {
            resultBody(tasks.single())
        } else {
            tasks.joinToString("\n\n") { task ->
                "## ${task.title}\n${resultBody(task)}"
            }
        }
        return "${summary.trim()}\n\n<DETAILS>\n${details.trim()}\n</DETAILS>"
    }

    private fun resultBody(task: TaskEntity): String = task.details
        .ifBlank { task.summary }
        .ifBlank { task.error }
        .ifBlank { "${task.title}：${task.status}" }

    private const val MAX_SUMMARY_SENTENCES = 3
    private val SUMMARY_SENTENCE_ENDINGS = setOf('。', '！', '？', '!', '?')
}
