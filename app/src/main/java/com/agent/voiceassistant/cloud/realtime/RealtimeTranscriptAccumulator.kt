package com.agent.voiceassistant.cloud.realtime

/**
 * StepFun may send either an incremental ASR fragment or the current complete transcript in a
 * delta event. Preserve true suffix deltas while never duplicating a repeated full snapshot.
 */
object RealtimeTranscriptAccumulator {
    fun merge(current: String, incoming: String): String {
        if (incoming.isBlank()) return current
        if (current.isBlank()) return incoming
        if (incoming == current || current.endsWith(incoming)) return current
        if (incoming.startsWith(current)) return incoming

        return current + incoming
    }
}
