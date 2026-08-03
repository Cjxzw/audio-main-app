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

    fun extract(markdown: String): DetailExtraction {
        val completeMatches = completeBlock.findAll(markdown).toList()
        var mainText = markdown.replace(completeBlock, "")
        val incompleteMatch = incompleteBlock.find(mainText)
        val incompleteDetails = incompleteMatch?.value?.substringAfter('>')?.trim().orEmpty()
        if (incompleteMatch != null) {
            mainText = mainText.removeRange(incompleteMatch.range)
        }
        val detailsText = buildList {
            completeMatches.map { it.groupValues[1].trim() }
                .filter(String::isNotBlank)
                .forEach(::add)
            if (incompleteDetails.isNotBlank()) add(incompleteDetails)
        }.joinToString("\n\n")
        return DetailExtraction(
            speakableText = mainText.trim(),
            hasMarkedDetails = completeMatches.isNotEmpty() || incompleteMatch != null,
            detailsText = detailsText,
        )
    }

    fun stripDetails(text: String): DetailExtraction = extract(text)

    data class DetailExtraction(
        val speakableText: String,
        val hasMarkedDetails: Boolean,
        val detailsText: String = "",
    )
}
