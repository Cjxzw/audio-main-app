package com.agent.voiceassistant.service

import timber.log.Timber

object DiagLog {
    private const val TAG = "VA_DIAG"

    fun i(event: String, detail: String = "", showInUi: Boolean = false) {
        val line = format(event, detail)
        Timber.tag(TAG).i(line)
        if (showInUi) {
            EventBus.emitLog(line)
        }
    }

    fun w(event: String, detail: String = "", showInUi: Boolean = false) {
        val line = format(event, detail)
        Timber.tag(TAG).w(line)
        if (showInUi) {
            EventBus.emitLog(line)
        }
    }

    private fun format(event: String, detail: String): String =
        if (detail.isBlank()) event else "$event | $detail"
}
