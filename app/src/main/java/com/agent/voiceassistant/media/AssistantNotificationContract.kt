package com.agent.voiceassistant.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object AssistantNotificationContract {
    const val CHANNEL_ID = "assistant_media_session"
    const val NOTIFICATION_ID = 41

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "枢卫 Main",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "枢卫 Main 运行状态与媒体控制"
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
