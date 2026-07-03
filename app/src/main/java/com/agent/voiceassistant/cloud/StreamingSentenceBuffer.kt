package com.agent.voiceassistant.cloud

class StreamingSentenceBuffer(
    private val minChars: Int = 10,
    private val maxChars: Int = 80,
) {
    private val buffer = StringBuilder()

    fun feed(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        val ready = mutableListOf<String>()
        for (ch in delta) {
            buffer.append(ch)
            if (shouldEmit(ch)) {
                ready += consume()
            }
        }
        if (buffer.length >= maxChars) {
            ready += consume()
        }
        return ready
    }

    fun flush(): String? {
        val text = buffer.toString().trim()
        buffer.clear()
        return text.takeIf { it.isNotEmpty() }
    }

    private fun shouldEmit(ch: Char): Boolean {
        if (buffer.length < minChars) return false
        return ch in sentenceEndings
    }

    private fun consume(): String {
        val text = buffer.toString().trim()
        buffer.clear()
        return text
    }

    private companion object {
        private val sentenceEndings = setOf('。', '！', '？', '.', '!', '?', ';', '；')
    }
}
