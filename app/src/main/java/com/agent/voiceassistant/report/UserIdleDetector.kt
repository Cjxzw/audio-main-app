package com.agent.voiceassistant.report

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * UserIdleDetector：用户空闲检测器。
 *
 * 当用户既不在说话也不在听 Bot 播报超过阈值时，触发空闲事件。
 *
 * 状态来源：
 * - 用户说话状态：[onUserStartedSpeaking] / [onUserStoppedSpeaking]
 * - Bot 说话状态：[onBotStartedSpeaking] / [onBotStoppedSpeaking]
 *
 * 空闲判定：
 * - 既不在说话也不在听 → 累积空闲时间
 * - 一旦活动 → 重置空闲计时
 * - 空闲超过 [idleThresholdMs] → 发射 [UserIdleListener.onUserIdle]
 */
class UserIdleDetector(
    private val idleThresholdMs: Long = 30_000L,  // 默认 30 秒
    private val checkIntervalMs: Long = 1_000L,
    private val listener: UserIdleListener
) {
    interface UserIdleListener {
        fun onUserIdle(idleSeconds: Long)
        fun onUserActive()
    }

    @Volatile private var userSpeaking = false
    @Volatile private var botSpeaking = false
    @Volatile private var lastActivityTime: Long = System.currentTimeMillis()

    private val _isIdle = MutableStateFlow(false)
    val isIdle = _isIdle.asStateFlow()

    private var scope: CoroutineScope? = null
    private var detectJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (detectJob?.isActive == true) return
        this.scope = scope
        detectJob = scope.launch(Dispatchers.Default) {
            Timber.i("UserIdleDetector started, threshold=${idleThresholdMs}ms")
            while (true) {
                val now = System.currentTimeMillis()
                val idleMs = now - lastActivityTime
                val isIdleNow = !userSpeaking && !botSpeaking && idleMs >= idleThresholdMs

                if (isIdleNow && !_isIdle.value) {
                    _isIdle.value = true
                    listener.onUserIdle(idleMs / 1000)
                    Timber.i("UserIdle: idle ${idleMs / 1000}s")
                } else if (!isIdleNow && _isIdle.value) {
                    _isIdle.value = false
                    listener.onUserActive()
                }
                delay(checkIntervalMs)
            }
        }
    }

    fun stop() {
        detectJob?.cancel()
        detectJob = null
        scope = null
    }

    fun onUserStartedSpeaking() {
        userSpeaking = true
        touchActivity()
    }

    fun onUserStoppedSpeaking() {
        userSpeaking = false
        touchActivity()
    }

    fun onBotStartedSpeaking() {
        botSpeaking = true
        touchActivity()
    }

    fun onBotStoppedSpeaking() {
        botSpeaking = false
        touchActivity()
    }

    private fun touchActivity() {
        lastActivityTime = System.currentTimeMillis()
    }
}
