package com.agent.voiceassistant.agent

object SpokenReplyPolicy {
    private val fencedCode = Regex("```.*?```", setOf(RegexOption.DOT_MATCHES_ALL))
    private val markdownPrefix = Regex("(?m)^\\s*(?:[-*+] |\\d+[.)] )")
    private val technicalPath = Regex("(?:/[A-Za-z0-9_.-]+){2,}|[A-Za-z]:\\\\")
    private val url = Regex("https?://\\S+")

    fun needsRewrite(text: String): Boolean =
        text.length > 120 ||
            text.contains("```") ||
            text.count { it == '\n' } >= 3 ||
            markdownPrefix.containsMatchIn(text) ||
            technicalPath.containsMatchIn(text) ||
            url.containsMatchIn(text)

    fun fallback(text: String, maxChars: Int = 120): String {
        val cleaned = text
            .replace(fencedCode, "详细代码已放在聊天窗口里。")
            .replace(url, "相关链接已放在聊天窗口里")
            .replace(technicalPath, "相关文件")
            .replace(markdownPrefix, "")
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return "详细结果已经放在聊天窗口里。"
        if (cleaned.length <= maxChars) return cleaned
        val end = cleaned
            .take(maxChars)
            .indexOfLast { it in setOf('。', '！', '？', '；', '!', '?', ';') }
        return if (end >= 20) cleaned.substring(0, end + 1) else cleaned.take(maxChars).trimEnd() + "。"
    }
}
