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
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

class MainAgentHarness {
    enum class State {
        IDLE,
        RUNNING,
        WAITING_NETWORK,
        WAITING_RETRY,
        WAITING_RECOVERY,
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
    private val retryInputs = Channel<QueuedInput>(Channel.UNLIMITED)

    @Volatile
    private var activeTurn: Deferred<AgentLoop.Outcome>? = null

    val state: StateFlow<State> = _state.asStateFlow()

    fun isWaiting(): Boolean = _state.value in setOf(State.WAITING_NETWORK, State.WAITING_RETRY, State.WAITING_RECOVERY)

    suspend fun awaitRetry(networkTimeout: Boolean): QueuedInput {
        _state.value = if (networkTimeout) State.WAITING_NETWORK else State.WAITING_RECOVERY
        return retryInputs.receive().also { _state.value = State.RUNNING }
    }

    fun resume(text: String): Boolean {
        if (_state.value !in setOf(State.WAITING_NETWORK, State.WAITING_RETRY, State.WAITING_RECOVERY)) return false
        _state.value = State.RUNNING
        retryInputs.trySend(QueuedInput(text))
        return true
    }

    suspend fun run(
        loop: AgentLoop,
        config: AgentLoop.Config,
        turnId: String? = null,
    ): AgentLoop.Outcome =
        turnMutex.withLock {
            coroutineScope {
                _state.value = State.RUNNING
                val turn = async { if (turnId == null) loop.run(config) else loop.run(config, turnId) }
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
