package com.agent.voiceassistant.agent

/**
 * Best-effort parser for the editable pseudo-reasoning response contract.
 * Tags are treated as loose section boundaries rather than strict XML.
 */
object ExperimentalReplyParser {
    data class Result(
        val thinking: String,
        val answer: String,
        val details: String,
        val recognizedTags: Boolean,
        val hasAnswerTag: Boolean,
    ) {
        val hasUsableAnswer: Boolean get() = answer.isNotBlank()
    }

    private enum class Section { OUTSIDE, THINKING, ANSWER, DETAILS }

    private data class Tag(
        val name: String,
        val closing: Boolean,
        val start: Int,
        val endExclusive: Int,
    )

    private val tagPattern = Regex(
        "<\\s*(/?)\\s*(thinking|working|answer|details?)\\s*>",
        setOf(RegexOption.IGNORE_CASE),
    )

    fun parse(raw: String): Result {
        if (raw.isBlank()) return Result("", "", "", recognizedTags = false, hasAnswerTag = false)
        val tags = findTagsOutsideCodeFences(raw)
        if (tags.isEmpty()) {
            return Result("", raw.trim(), "", recognizedTags = false, hasAnswerTag = false)
        }

        val thinking = mutableListOf<String>()
        val answer = mutableListOf<String>()
        val details = mutableListOf<String>()
        val outside = mutableListOf<String>()
        var section = Section.OUTSIDE
        var cursor = 0
        var hasAnswerTag = false

        fun collect(text: String) {
            val value = text.trim()
            if (value.isBlank()) return
            when (section) {
                Section.THINKING -> thinking += value
                Section.ANSWER -> answer += value
                Section.DETAILS -> details += value
                Section.OUTSIDE -> outside += value
            }
        }

        tags.forEach { tag ->
            collect(raw.substring(cursor, tag.start))
            cursor = tag.endExclusive
            val target = when (tag.name) {
                "thinking", "working" -> Section.THINKING
                "answer" -> Section.ANSWER
                else -> Section.DETAILS
            }
            if (!tag.closing) {
                section = target
                if (target == Section.ANSWER) hasAnswerTag = true
            } else {
                // A misplaced close tag still provides a useful boundary.
                section = Section.OUTSIDE
            }
        }
        collect(raw.substring(cursor))

        if (outside.isNotEmpty()) {
            when {
                answer.isNotEmpty() || hasAnswerTag -> answer += outside
                thinking.isNotEmpty() || details.isNotEmpty() -> answer += outside
                else -> answer += outside
            }
        }
        return Result(
            thinking = thinking.joinToString("\n\n").trim(),
            answer = answer.joinToString("\n\n").trim(),
            details = details.joinToString("\n\n").trim(),
            recognizedTags = true,
            hasAnswerTag = hasAnswerTag,
        )
    }

    fun parseStreaming(raw: String): Result {
        val lastOpen = raw.lastIndexOf('<')
        if (lastOpen < 0) return parse(raw)
        val tail = raw.substring(lastOpen)
        if ('>' in tail) return parse(raw)
        val compact = tail.lowercase().filterNot(Char::isWhitespace)
        val couldBeBoundary = STREAMING_TAG_PREFIXES.any { it.startsWith(compact) }
        return parse(if (couldBeBoundary) raw.substring(0, lastOpen) else raw)
    }

    private fun findTagsOutsideCodeFences(raw: String): List<Tag> {
        val fenceRanges = mutableListOf<IntRange>()
        var fenceStart = raw.indexOf("```")
        while (fenceStart >= 0) {
            val close = raw.indexOf("```", fenceStart + 3)
            val end = if (close >= 0) close + 2 else raw.lastIndex
            fenceRanges += fenceStart..end
            if (close < 0) break
            fenceStart = raw.indexOf("```", close + 3)
        }
        return tagPattern.findAll(raw).mapNotNull { match ->
            if (fenceRanges.any { match.range.first in it }) return@mapNotNull null
            Tag(
                name = match.groupValues[2].lowercase().removeSuffix("s").let {
                    if (it == "detail") "details" else it
                },
                closing = match.groupValues[1].isNotEmpty(),
                start = match.range.first,
                endExclusive = match.range.last + 1,
            )
        }.toList()
    }

    private val STREAMING_TAG_PREFIXES = listOf(
        "<thinking>", "</thinking>",
        "<working>", "</working>",
        "<answer>", "</answer>",
        "<detail>", "</detail>",
        "<details>", "</details>",
    )
}
