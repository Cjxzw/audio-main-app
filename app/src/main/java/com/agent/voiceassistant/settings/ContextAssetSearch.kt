package com.agent.voiceassistant.settings

/** Read-only literal filtering shared by the three context management pages. */
internal object ContextAssetSearch {
    fun matches(query: String, fields: List<String>): Boolean {
        val keyword = query.trim()
        return keyword.isEmpty() || fields.any { it.contains(keyword, ignoreCase = true) }
    }
}
