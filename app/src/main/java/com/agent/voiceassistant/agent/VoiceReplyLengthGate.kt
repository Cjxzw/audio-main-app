package com.agent.voiceassistant.agent

/** Tracks spoken, non-punctuation characters without counting display-only details. */
internal class VoiceReplyLengthGate(
    private val threshold: Int = DEFAULT_THRESHOLD,
) {
    private var count = 0
    var exceeded: Boolean = false
        private set

    fun observe(text: String): Boolean {
        if (text.isEmpty() || exceeded) return false
        count += text.count(Char::isLetterOrDigit)
        if (count <= threshold) return false
        exceeded = true
        return true
    }

    companion object {
        const val DEFAULT_THRESHOLD = 30

        fun countVisible(text: String): Int {
            val withoutDetails = ReplyDetailPolicy.stripDetails(text).speakableText
            val visible = SpokenReplyPolicy.withoutFencedDetails(withoutDetails)
            return visible.count(Char::isLetterOrDigit)
        }
    }
}
