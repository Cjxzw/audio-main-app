package com.agent.voiceassistant.service

import com.agent.voiceassistant.ui.ChatMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {

    private val _logs = MutableSharedFlow<ServiceLog>(
        replay = 50,
        extraBufferCapacity = 64
    )
    val logs: SharedFlow<ServiceLog> = _logs.asSharedFlow()

    private val _states = MutableSharedFlow<ServiceState>(
        replay = 1,
        extraBufferCapacity = 8
    )
    val states: SharedFlow<ServiceState> = _states.asSharedFlow()

    private val _pendingCounts = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 8
    )
    val pendingCounts: SharedFlow<Int> = _pendingCounts.asSharedFlow()

    private val _volumeEvents = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 8
    )
    val volumeEvents: SharedFlow<Float> = _volumeEvents.asSharedFlow()

    private val _chatMessages = MutableSharedFlow<ChatMessage>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val chatMessages: SharedFlow<ChatMessage> = _chatMessages.asSharedFlow()

    fun emitLog(message: String) {
        _logs.tryEmit(ServiceLog(System.currentTimeMillis(), message))
    }

    fun emitState(state: ServiceState) {
        _states.tryEmit(state)
    }

    fun emitPendingCount(count: Int) {
        _pendingCounts.tryEmit(count)
    }

    fun emitVolume(level: Float) {
        _volumeEvents.tryEmit(level)
    }

    fun emitChatMessage(msg: ChatMessage) {
        _chatMessages.tryEmit(msg)
    }
}

data class ServiceLog(
    val timestamp: Long,
    val message: String
)

enum class ServiceState {
    IDLE,
    DORMANT,
    INITIALIZING,
    READY,
    LISTENING,
    FAILED
}
