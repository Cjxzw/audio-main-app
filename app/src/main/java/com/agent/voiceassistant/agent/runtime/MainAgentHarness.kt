package com.agent.voiceassistant.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

class MainAgentHarness {
    enum class State {
        IDLE,
        RUNNING,
        CANCELLING,
        FAILED,
    }

    data class QueuedInput(
        val text: String,
        val queuedAt: Long = System.currentTimeMillis(),
    )

    private val turnMutex = Mutex()
    private val queueLock = Any()
    private val steeringQueue = ArrayDeque<QueuedInput>()
    private val followUpQueue = ArrayDeque<QueuedInput>()
    private val _state = MutableStateFlow(State.IDLE)

    @Volatile
    private var activeTurn: Deferred<AgentLoop.Outcome>? = null

    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun run(loop: AgentLoop, config: AgentLoop.Config): AgentLoop.Outcome =
        turnMutex.withLock {
            coroutineScope {
                _state.value = State.RUNNING
                val turn = async { loop.run(config) }
                activeTurn = turn
                try {
                    turn.await().also { _state.value = State.IDLE }
                } catch (cancelled: CancellationException) {
                    _state.value = State.IDLE
                    throw cancelled
                } catch (error: Throwable) {
                    _state.value = State.FAILED
                    throw error
                } finally {
                    activeTurn = null
                }
            }
        }

    fun steer(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        synchronized(queueLock) { steeringQueue.addLast(QueuedInput(normalized)) }
    }

    fun followUp(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        synchronized(queueLock) { followUpQueue.addLast(QueuedInput(normalized)) }
    }

    fun drainSteering(): List<QueuedInput> = synchronized(queueLock) {
        buildList {
            while (steeringQueue.isNotEmpty()) add(steeringQueue.removeFirst())
        }
    }

    fun drainFollowUps(): List<QueuedInput> = synchronized(queueLock) {
        buildList {
            while (followUpQueue.isNotEmpty()) add(followUpQueue.removeFirst())
        }
    }

    fun abort(reason: String = "用户取消当前回合"): Boolean {
        val turn = activeTurn ?: return false
        if (!turn.isActive) return false
        _state.value = State.CANCELLING
        turn.cancel(CancellationException(reason))
        return true
    }
}
