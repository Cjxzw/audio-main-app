package com.agent.voiceassistant.service

object RealtimeReportPolicy {
    fun canReport(ready: Boolean, userSpeaking: Boolean, responseInFlight: Boolean,
                  pendingTools: Int, toolFollowUp: Boolean): Boolean =
        ready && !userSpeaking && !responseInFlight && pendingTools == 0 && !toolFollowUp
}
