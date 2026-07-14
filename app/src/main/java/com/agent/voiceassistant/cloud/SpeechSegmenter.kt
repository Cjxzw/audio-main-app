package com.agent.voiceassistant.cloud

class SpeechSegmenter(
    private val minHardChars: Int = 70,
    private val softTargetChars: Int = 150,
    private val maxChars: Int = 240,
) {
    private val buffer = StringBuilder()

    fun feed(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        val ready = mutableListOf<String>()
        for (ch in delta) {
            buffer.append(ch)
            val lastIndex = buffer.lastIndex
            when {
                shouldHardEmit(lastIndex) -> ready += consume(lastIndex + 1)
                shouldSoftEmit(lastIndex) -> ready += consume(lastIndex + 1)
                buffer.length >= maxChars -> ready += consume(bestBreakIndex())
            }
        }
        return ready.filter { it.isNotBlank() }
    }

    fun flush(): String? {
        val text = buffer.toString().trim()
        buffer.clear()
        return text.takeIf { it.isNotEmpty() }
    }

    private fun shouldHardEmit(index: Int): Boolean {
        if (buffer.length < minHardChars) return false
        val ch = buffer[index]
        if (ch !in hardEndings) return false
        if (isProtectedBreak(index, ch)) return false
        if (hasUnclosedPair() && buffer.length < softTargetChars) return false
        return true
    }

    private fun shouldSoftEmit(index: Int): Boolean {
        if (buffer.length < softTargetChars) return false
        val ch = buffer[index]
        if (ch !in softEndings) return false
        if (isProtectedBreak(index, ch)) return false
        if (hasUnclosedPair() && buffer.length < maxChars) return false
        return true
    }

    private fun bestBreakIndex(): Int {
        val upper = (buffer.length - 1).coerceAtMost(maxChars - 1)
        for (i in upper downTo minHardChars) {
            val ch = buffer[i]
            if (ch in hardEndings && !isProtectedBreak(i, ch)) return i + 1
        }
        for (i in upper downTo minHardChars) {
            val ch = buffer[i]
            if (ch in softEndings && !isProtectedBreak(i, ch)) return i + 1
        }
        return upper + 1
    }

    private fun consume(endExclusive: Int): String {
        val boundedEnd = endExclusive.coerceIn(0, buffer.length)
        val text = buffer.substring(0, boundedEnd).trim()
        buffer.delete(0, boundedEnd)
        return text
    }

    private fun isProtectedBreak(index: Int, ch: Char): Boolean {
        val prev = buffer.getOrNull(index - 1)
        val next = buffer.getOrNull(index + 1)
        val token = currentToken(index).lowercase()
        if (token.startsWith("http") || token.startsWith("www.") || token.contains("@")) return true

        return when (ch) {
            '.' -> prev?.isLetterOrDigit() == true || next?.isLetterOrDigit() == true
            ',' -> prev?.isDigit() == true || next?.isDigit() == true
            ':' -> (prev?.isDigit() == true && next?.isDigit() == true) || token.startsWith("http")
            else -> false
        }
    }

    private fun currentToken(index: Int): String {
        var start = index
        while (start > 0 && !buffer[start - 1].isWhitespace()) start--
        var end = index + 1
        while (end < buffer.length && !buffer[end].isWhitespace()) end++
        return buffer.substring(start, end)
    }

    private fun hasUnclosedPair(): Boolean {
        var chineseQuote = false
        var quote = false
        var parens = 0
        var brackets = 0
        var bookTitle = false
        for (ch in buffer) {
            when (ch) {
                '“' -> chineseQuote = true
                '”' -> chineseQuote = false
                '"' -> quote = !quote
                '（', '(' -> parens++
                '）', ')' -> if (parens > 0) parens--
                '【', '[' -> brackets++
                '】', ']' -> if (brackets > 0) brackets--
                '《' -> bookTitle = true
                '》' -> bookTitle = false
            }
        }
        return chineseQuote || quote || parens > 0 || brackets > 0 || bookTitle
    }

    private fun StringBuilder.getOrNull(index: Int): Char? =
        if (index in 0 until length) this[index] else null

    private companion object {
        private val hardEndings = setOf('。', '！', '？', '；', '!', '?', ';', '.')
        private val softEndings = setOf('：', ':', '\n')
    }
}
