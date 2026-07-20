package com.agent.voiceassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.agent.voiceassistant.service.DiagLog
import com.agent.voiceassistant.service.VoiceAgentService
import timber.log.Timber

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
            DiagLog.i("media.receiver.ignore", "action=${intent.action}")
            return
        }
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        if (event == null) {
            DiagLog.w("media.receiver.no_event", "ordered=$isOrderedBroadcast", showInUi = true)
            return
        }
        DiagLog.i(
            "media.receiver.event",
            "key=${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} repeat=${event.repeatCount} ordered=$isOrderedBroadcast",
            showInUi = true,
        )
        if (event.action != KeyEvent.ACTION_DOWN) return

        Timber.i("MediaButtonReceiver keyCode=${event.keyCode}")
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                DiagLog.i("media.receiver.toggle", "key=${KeyEvent.keyCodeToString(event.keyCode)}", showInUi = true)
                VoiceAgentService.toggle(context)
                if (isOrderedBroadcast) abortBroadcast()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                DiagLog.i("media.receiver.wake", "key=${KeyEvent.keyCodeToString(event.keyCode)}", showInUi = true)
                VoiceAgentService.wake(context)
                if (isOrderedBroadcast) abortBroadcast()
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                DiagLog.i("media.receiver.sleep", "key=${KeyEvent.keyCodeToString(event.keyCode)}", showInUi = true)
                VoiceAgentService.sleep(context)
                if (isOrderedBroadcast) abortBroadcast()
            }
            else -> DiagLog.i("media.receiver.unhandled", "key=${KeyEvent.keyCodeToString(event.keyCode)}")
        }
    }
}
