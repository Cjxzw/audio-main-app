package com.agent.voiceassistant

import android.app.Activity
import android.os.Bundle
import com.agent.voiceassistant.service.DiagLog
import com.agent.voiceassistant.service.VoiceAgentService
import timber.log.Timber

class AssistEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("AssistEntryActivity invoked: action=${intent?.action}")
        DiagLog.i("assist.entry", "action=${intent?.action}", showInUi = true)
        VoiceAgentService.toggle(this)
        finish()
        overridePendingTransition(0, 0)
    }
}
