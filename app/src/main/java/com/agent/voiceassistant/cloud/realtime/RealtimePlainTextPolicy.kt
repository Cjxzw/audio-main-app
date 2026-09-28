package com.agent.voiceassistant.cloud.realtime

/** Removes presentation-only Markdown when a provider ignores the Realtime plain-text contract. */
object RealtimePlainTextPolicy {
    fun normalize(text: String): String = text
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1（$2）")
        .trim()
}
