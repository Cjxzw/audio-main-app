package com.agent.voiceassistant.service

import timber.log.Timber

object DiagLog {
    fun i(event: String, detail: String = "", showInUi: Boolean = false) {
        val line = format(event, detail)
        Timber.tag(tagFor(event)).i(line)
        if (showInUi) {
            EventBus.emitLog(line)
        }
    }

    fun w(event: String, detail: String = "", showInUi: Boolean = false) {
        val line = format(event, detail)
        Timber.tag(tagFor(event)).w(line)
        if (showInUi) {
            EventBus.emitLog(line)
        }
    }

    private fun format(event: String, detail: String): String =
        if (detail.isBlank()) event else "$event | $detail"

    private fun tagFor(event: String): String {
        val domain = when {
            event.startsWith("agent.tool.") -> "TOOL"
            event.startsWith("media3.") -> "MEDIA"
            event.startsWith("api.") || event.startsWith("service.") || event.startsWith("assist.") -> "SERVICE"
            else -> event.substringBefore('.').uppercase().replace(Regex("[^A-Z0-9_]"), "_")
        }
        return "VA_${domain.ifBlank { "DIAG" }}"
    }
}
