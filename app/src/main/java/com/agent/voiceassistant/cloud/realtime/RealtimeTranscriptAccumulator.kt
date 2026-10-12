package com.agent.voiceassistant.cloud.realtime

/** Applies the official Realtime event semantics: a delta is appended to its item transcript. */
object RealtimeTranscriptAccumulator {
    fun appendDelta(current: String, incoming: String): String {
        if (incoming.isBlank()) return current
        return current + incoming
    }

    /** Kept as a source-compatible alias for callers outside the realtime pipeline. */
    fun merge(current: String, incoming: String): String = appendDelta(current, incoming)
}
