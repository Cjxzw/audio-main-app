package com.agent.voiceassistant.agent

object LongDetailsPolicy {
    const val MAX_INLINE_CHARACTERS = 1_000
    private const val NOTICE_PREFIX = "完整内容已转储至 `"
    private const val NOTICE_SUFFIX = "`"

    data class Result(
        val content: String,
        val dumpedPath: String? = null,
    )

    fun normalize(markdown: String, dump: (String) -> String): Result {
        val extraction = ReplyDetailPolicy.extract(markdown)
        if (!extraction.hasMarkedDetails || extraction.detailsText.length <= MAX_INLINE_CHARACTERS) {
            return Result(markdown)
        }
        val path = dump(extraction.detailsText)
        val content = buildString {
            if (extraction.speakableText.isNotBlank()) append(extraction.speakableText).append("\n\n")
            append(ReplyDetailPolicy.OPEN_TAG).append('\n')
            append(NOTICE_PREFIX).append(path).append(NOTICE_SUFFIX).append('\n')
            append(ReplyDetailPolicy.CLOSE_TAG)
        }
        return Result(content, path)
    }

    fun dumpedPath(detailsText: String): String? {
        val trimmed = detailsText.trim()
        if (!trimmed.startsWith(NOTICE_PREFIX) || !trimmed.endsWith(NOTICE_SUFFIX)) return null
        return trimmed.removePrefix(NOTICE_PREFIX).removeSuffix(NOTICE_SUFFIX)
            .takeIf { it.startsWith("/workspace/") && it.endsWith(".md") }
    }
}
