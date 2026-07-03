package com.agent.voiceassistant

import android.app.Activity
import android.os.Bundle
import com.agent.voiceassistant.service.VoiceAgentService
import timber.log.Timber

class AssistEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("AssistEntryActivity invoked: action=${intent?.action}")
        VoiceAgentService.wake(this)
        finish()
        overridePendingTransition(0, 0)
    }
}
