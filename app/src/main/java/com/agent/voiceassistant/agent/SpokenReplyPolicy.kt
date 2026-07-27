package com.agent.voiceassistant.agent

object SpokenReplyPolicy {
    const val DETAILS_NOTICE = "该回复中有详细信息，请查看手机。"

    private val markdownPrefix = Regex("(?m)^\\s*(?:[-*+] |\\d+[.)] )")
    private val markdownHeading = Regex("(?m)^\\s*#{1,6}\\s*")
    private val markdownEmphasis = Regex("[*_]{1,3}")
    private val markdownLink = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val technicalPath = Regex("(?:/[A-Za-z0-9_.-]+){2,}|[A-Za-z]:\\\\")
    private val url = Regex("https?://\\S+")

    fun needsRewrite(text: String): Boolean =
        text.length > 120 ||
            text.contains("```") ||
            text.count { it == '\n' } >= 3 ||
            markdownPrefix.containsMatchIn(text) ||
            technicalPath.containsMatchIn(text) ||
            url.containsMatchIn(text)

    fun hasFencedDetails(text: String): Boolean = text.contains("```")

    fun withoutFencedDetails(text: String): String {
        val firstFence = text.indexOf("```")
        if (firstFence < 0) return text
        val output = StringBuilder(text.length)
        var cursor = 0
        while (cursor < text.length) {
            val opening = text.indexOf("```", cursor)
            if (opening < 0) {
                output.append(text, cursor, text.length)
                break
            }
            output.append(text, cursor, opening)
            val closing = text.indexOf("```", opening + 3)
            if (closing < 0) break
            cursor = closing + 3
        }
        return output.toString()
    }

    fun isDetailsOnly(text: String): Boolean =
        hasFencedDetails(text) && withoutFencedDetails(text).isBlank()

    fun hasUnsupportedUnfencedStructure(text: String): Boolean {
        val visible = withoutFencedDetails(text).trim()
        if (visible.startsWith('{') || visible.startsWith('[')) return true
        return Regex("<[A-Za-z][^>]*>.*</[A-Za-z][^>]*>", RegexOption.DOT_MATCHES_ALL)
            .containsMatchIn(visible)
    }

    fun fallback(text: String): String {
        val detailExtraction = ReplyDetailPolicy.stripDetails(text)
        val hasCodeFence = hasFencedDetails(detailExtraction.speakableText)
        val visible = withoutFencedDetails(detailExtraction.speakableText)
        val withoutTables = removeMarkdownTables(visible)
        if (isDisplayOnlyStructure(withoutTables)) {
            return if (detailExtraction.hasMarkedDetails || hasCodeFence) DETAILS_NOTICE else ""
        }
        val cleaned = withoutTables
            .replace(url, "相关链接已放在聊天窗口里")
            .replace(technicalPath, "相关文件")
            .replace(markdownLink, "$1")
            .replace(markdownHeading, "")
            .replace(markdownPrefix, "")
            .replace(markdownEmphasis, "")
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val shouldAnnounceDetails = detailExtraction.hasMarkedDetails || hasCodeFence
        if (cleaned.isBlank()) return if (shouldAnnounceDetails) DETAILS_NOTICE else ""
        return if (shouldAnnounceDetails) "$cleaned $DETAILS_NOTICE" else cleaned
    }

    private fun isDisplayOnlyStructure(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith('{') ||
            trimmed.startsWith('[') ||
            Regex("^<[A-Za-z][^>]*>.*</[A-Za-z][^>]*>", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(trimmed)
    }

    private fun removeMarkdownTables(text: String): String {
        val lines = text.lines()
        val tableLines = mutableSetOf<Int>()
        lines.forEachIndexed { index, line ->
            if (!TABLE_SEPARATOR.matches(line)) return@forEachIndexed
            if (index > 0 && lines[index - 1].contains('|')) tableLines += index - 1
            tableLines += index
            var cursor = index + 1
            while (cursor < lines.size && lines[cursor].contains('|') && lines[cursor].isNotBlank()) {
                tableLines += cursor
                cursor += 1
            }
        }
        return lines.filterIndexed { index, _ -> index !in tableLines }.joinToString("\n")
    }

    private val TABLE_SEPARATOR = Regex(
        "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$",
    )
}
