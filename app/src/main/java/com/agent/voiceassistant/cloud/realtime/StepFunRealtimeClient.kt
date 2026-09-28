package com.agent.voiceassistant.cloud.realtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.settings.StepFunRealtimeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * A single StepFun Realtime session. Conversation persistence and audio playback deliberately
 * remain outside this class so reconnect policy is owned by the voice pipeline.
 */
class StepFunRealtimeClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : AutoCloseable {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED,
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state = _state.asStateFlow()

    private val inbound = Channel<StepFunRealtimeProtocol.Event>(Channel.UNLIMITED)
    val events: Flow<StepFunRealtimeProtocol.Event> = inbound.receiveAsFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var sessionReady: CompletableDeferred<Unit>? = null

    suspend fun connect(apiKey: String, config: StepFunRealtimeConfig) {
        check(_state.value == ConnectionState.DISCONNECTED || _state.value == ConnectionState.FAILED) {
            "Realtime 会话已连接或正在连接"
        }
        require(apiKey.isNotBlank()) { "未配置 StepFun API Key" }
        _state.value = ConnectionState.CONNECTING
        val ready = CompletableDeferred<Unit>()
        sessionReady = ready
        val request = Request.Builder()
            .url(StepFunRealtimeProtocol.websocketUrl(config.modelId))
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .build()
        socket = httpClient.newWebSocket(request, listener(ready))
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun updateSession(
        config: StepFunRealtimeConfig,
        instructions: String,
        tools: List<CloudSpeechClient.ToolDefinition>,
    ) = send(StepFunRealtimeProtocol.sessionUpdate(config, instructions, tools))

    fun appendAudio(pcm16Le: ByteArray) {
        if (pcm16Le.isEmpty()) return
        send(StepFunRealtimeProtocol.appendAudio(Base64.getEncoder().encodeToString(pcm16Le)))
    }

    fun commitAudio() = send(StepFunRealtimeProtocol.commitAudio())

    fun createResponse() = send(StepFunRealtimeProtocol.createResponse())

    fun sendText(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        send(StepFunRealtimeProtocol.createTextMessage(normalized))
        createResponse()
    }

    fun sendContext(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        send(StepFunRealtimeProtocol.createContextMessage(normalized))
    }

    fun sendFunctionOutput(callId: String, output: String) {
        send(StepFunRealtimeProtocol.functionOutput(callId, output))
    }

    fun cancelResponse() = send(StepFunRealtimeProtocol.cancelResponse())

    fun truncateAssistantAudio(itemId: String, playedAudioMs: Long) {
        send(StepFunRealtimeProtocol.truncateAssistantAudio(itemId, playedAudioMs))
    }

    override fun close() {
        sessionReady?.cancel()
        sessionReady = null
        socket?.close(1000, "client closed")
        socket = null
        _state.value = ConnectionState.DISCONNECTED
    }

    fun shutdown() {
        close()
        inbound.close()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun send(event: kotlinx.serialization.json.JsonObject) {
        check(_state.value == ConnectionState.CONNECTED) { "Realtime 会话尚未就绪" }
        check(socket?.send(event.toString()) == true) { "Realtime 消息发送失败" }
    }

    private fun listener(ready: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = StepFunRealtimeProtocol.parse(text)
            if (event is StepFunRealtimeProtocol.Event.SessionCreated) {
                _state.value = ConnectionState.CONNECTED
                ready.complete(Unit)
            }
            if (event is StepFunRealtimeProtocol.Event.Error && !ready.isCompleted) {
                _state.value = ConnectionState.FAILED
                ready.completeExceptionally(IOException(event.message))
            }
            inbound.trySend(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val wasConnected = _state.value == ConnectionState.CONNECTED
            if (_state.value != ConnectionState.DISCONNECTED) _state.value = ConnectionState.DISCONNECTED
            if (!ready.isCompleted) ready.completeExceptionally(IOException("Realtime 连接已关闭: $code"))
            if (wasConnected) {
                inbound.trySend(StepFunRealtimeProtocol.Event.Error(null, "Realtime 连接已关闭：$code $reason"))
            }
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            _state.value = ConnectionState.FAILED
            if (!ready.isCompleted) ready.completeExceptionally(throwable)
            inbound.trySend(StepFunRealtimeProtocol.Event.Error(null, "Realtime 连接失败：${throwable.message ?: throwable.javaClass.simpleName}"))
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000L
    }
}
