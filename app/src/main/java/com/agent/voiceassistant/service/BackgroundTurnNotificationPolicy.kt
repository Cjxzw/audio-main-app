package com.agent.voiceassistant.service

object BackgroundTurnNotificationPolicy {
    fun shouldNotify(userTurn: Boolean, visible: Boolean, turnId: String, realtimeActive: Boolean = false): Boolean =
        userTurn && !realtimeActive && !visible && turnId.isNotBlank()

    fun completedStatus(failed: Boolean): String? = if (failed) null else "任务已完成"
}
