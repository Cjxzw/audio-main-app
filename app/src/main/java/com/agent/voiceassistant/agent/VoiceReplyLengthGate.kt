package com.agent.voiceassistant.agent

/** Tracks Han and non-Han Unicode code points independently. */
internal class VoiceReplyLengthGate(
    private val threshold: Int = DEFAULT_THRESHOLD,
) {
    private var hanCount = 0
    private var nonHanCount = 0
    var exceeded: Boolean = false
        private set

    fun observe(text: String): Boolean {
        if (text.isEmpty() || exceeded) return false
        text.codePoints().forEach { codePoint ->
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                hanCount++
            } else {
                nonHanCount++
            }
        }
        if (hanCount < threshold && nonHanCount < threshold) return false
        exceeded = true
        return true
    }

    companion object {
        const val DEFAULT_THRESHOLD = 50

        fun shouldSummarize(text: String, threshold: Int = DEFAULT_THRESHOLD): Boolean {
            var han = 0
            var nonHan = 0
            text.codePoints().forEach { codePoint ->
                if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) han++ else nonHan++
            }
            return han >= threshold || nonHan >= threshold
        }
    }
}
