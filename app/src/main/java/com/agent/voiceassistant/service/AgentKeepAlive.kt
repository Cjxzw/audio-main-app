package com.agent.voiceassistant.service

import android.content.Context
import android.os.PowerManager
import timber.log.Timber

/** Keeps the CPU awake only while an active agent or realtime operation owns the lease. */
class AgentKeepAlive(context: Context) {
    private val wakeLock = context.applicationContext
        .getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hanwo:AgentLoop")
        .apply { setReferenceCounted(false) }

    @Synchronized
    fun acquire(reason: String) {
        if (wakeLock.isHeld) return
        wakeLock.acquire()
        Timber.i("AgentKeepAlive acquired reason=$reason")
    }

    @Synchronized
    fun release(reason: String) {
        if (!wakeLock.isHeld) return
        wakeLock.release()
        Timber.i("AgentKeepAlive released reason=$reason")
    }

    @Synchronized
    fun release() = release("cleanup")
}
