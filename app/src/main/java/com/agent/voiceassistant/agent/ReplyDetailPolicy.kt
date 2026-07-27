package com.agent.voiceassistant.agent

object ReplyDetailPolicy {
    const val OPEN_TAG = "<DETAILS>"
    const val CLOSE_TAG = "</DETAILS>"

    private val completeBlock = Regex(
        "<DETAILS?>(.*?)</DETAILS?>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val incompleteBlock = Regex(
        "<DETAILS?>.*$",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val boundaryTag = Regex("</?DETAILS?>", RegexOption.IGNORE_CASE)

    fun forDisplay(markdown: String): String = markdown
        .replace(boundaryTag, "")
        .trim()

    fun stripDetails(text: String): DetailExtraction {
        val hasMarkedDetails = completeBlock.containsMatchIn(text) || incompleteBlock.containsMatchIn(text)
        val withoutComplete = text.replace(completeBlock, "")
        val speakable = withoutComplete.replace(incompleteBlock, "")
        return DetailExtraction(speakable, hasMarkedDetails)
    }

    data class DetailExtraction(
        val speakableText: String,
        val hasMarkedDetails: Boolean,
    )
}
