package com.agent.voiceassistant.audio

object AudioFeedbackPolicy {
    fun allowAutomaticFeedback(inputSource: String, muteTextTurns: Boolean): Boolean =
        inputSource != "text" || !muteTextTurns

    fun allowProactiveTaskReports(muteTextTurns: Boolean): Boolean = !muteTextTurns

    fun taskRequiresSilentReport(inputJson: String): Boolean =
        Regex("\"_silent_audio\"\\s*:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(inputJson)
}
