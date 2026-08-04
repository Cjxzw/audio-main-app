package com.agent.voiceassistant.hub

import android.content.Context
import com.agent.voiceassistant.tasks.TaskRepository
import com.agent.voiceassistant.tasks.TaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun buildHubWebSocketUrl(settings: HubSettings): String {
    val base = settings.baseUrl.trimEnd('/').toHttpUrl()
    val httpUrl = base.newBuilder()
        .addPathSegments("ws/main")
        .addQueryParameter("token", settings.token)
        .addQueryParameter("client_id", settings.clientId)
        .addQueryParameter("name", settings.deviceName)
        .addQueryParameter("device_id", settings.clientId)
        .build()
        .toString()
    return if (base.scheme == "https") httpUrl.replaceFirst("https://", "wss://")
    else httpUrl.replaceFirst("http://", "ws://")
}

class HubClient(context: Context) {
    private val appContext = context.applicationContext
    private val configRepository = HubConfigRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskRepository = TaskRepository(appContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _state = MutableStateFlow(HubConnectionState.DISCONNECTED)
    private val _facts = MutableStateFlow(HubFacts())
    private val pendingActions = ConcurrentHashMap<String, CompletableDeferred<HubActionResult>>()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentSettings: HubSettings? = null
    private var factsRefreshWaiter: CompletableDeferred<Unit>? = null
    private var factsSyncRequested = false

    val state: StateFlow<HubConnectionState> = _state.asStateFlow()
    val facts: StateFlow<HubFacts> = _facts.asStateFlow()

    fun connect(settings: HubSettings = configRepository.load()) {
        currentSettings = settings
        reconnectJob?.cancel()
        val previousSocket = socket
        socket = null
        previousSocket?.cancel()
        if (!settings.enabled || settings.token.isBlank()) {
            _state.value = HubConnectionState.DISABLED
            return
        }
        _state.value = HubConnectionState.CONNECTING
        val url = runCatching { buildHubWebSocketUrl(settings) }
            .getOrElse {
                _state.value = HubConnectionState.ERROR
                Timber.w(it, "Invalid Hub WebSocket URL")
                return
            }
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    fun disconnect() {
        reconnectJob?.cancel()
        currentSettings = null
        val closingSocket = socket
        socket = null
        closingSocket?.close(1000, "client disconnect")
        synchronized(this) {
            factsRefreshWaiter?.complete(Unit)
            factsRefreshWaiter = null
        }
        _state.value = HubConnectionState.DISCONNECTED
    }

    suspend fun refreshFacts(timeoutMs: Long = 1_500L): HubFacts {
        if (_state.value != HubConnectionState.CONNECTED) return _facts.value
        val waiter = CompletableDeferred<Unit>()
        synchronized(this) {
            factsRefreshWaiter?.complete(Unit)
            factsRefreshWaiter = waiter
        }
        val request = buildJsonObject {
            put("type", "facts.request")
            put("lastEventId", _facts.value.eventId)
            put("factsVersion", _facts.value.factsVersion)
            put("forceSnapshot", true)
        }
        if (socket?.send(request.toString()) != true) {
            synchronized(this) { if (factsRefreshWaiter === waiter) factsRefreshWaiter = null }
            return _facts.value
        }
        withTimeoutOrNull(timeoutMs) { waiter.await() }
        synchronized(this) { if (factsRefreshWaiter === waiter) factsRefreshWaiter = null }
        return _facts.value
    }

    suspend fun submitAction(
        actionType: String,
        payload: JsonObject,
        turnId: String,
        conversationId: String,
        idempotencyKey: String? = null,
    ): HubActionResult {
        check(_state.value == HubConnectionState.CONNECTED) { "枢卫 Hub 未连接" }
        val settings = currentSettings ?: configRepository.load()
        val requestId = "req_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<HubActionResult>()
        pendingActions[requestId] = deferred
        val factsVersion = _facts.value.factsVersion
        val body = buildJsonObject {
            put("type", "action.submit")
            put("payload", buildJsonObject {
                put("requestId", requestId)
                put("clientId", settings.clientId)
                put("turnId", turnId)
                put("conversationId", conversationId)
                put("actionType", actionType)
                put("factsVersion", factsVersion)
                put("idempotencyKey", idempotencyKey ?: requestId)
                put("payload", payload)
            })
        }
        return try {
            repeat(2) { attempt ->
                if (socket?.send(body.toString()) != true) {
                    if (attempt == 1) error("无法发送 Hub action")
                } else {
                    val result = withTimeoutOrNull(15_000L) { deferred.await() }
                    if (result != null) return result
                }
            }
            error("Hub action 等待确认超时")
        } finally {
            pendingActions.remove(requestId)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            _state.value = HubConnectionState.CONNECTED
            val settings = currentSettings ?: return
            webSocket.send(buildJsonObject {
                put("type", "main.register")
                put("clientId", settings.clientId)
                put("name", settings.deviceName)
                put("deviceId", settings.clientId)
                put("platform", "android")
                put("protocolVersion", 2)
                put("capabilities", buildJsonObject {
                    put("voiceInput", true)
                    put("tts", true)
                    put("localStore", true)
                })
                put("lastEventId", _facts.value.eventId)
            }.toString())
            Timber.i("Hub connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            runCatching { handleMessage(json.parseToJsonElement(text).jsonObject) }
                .onFailure { Timber.w(it, "Hub message parse failed") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            _state.value = if (response?.code == 401 || response?.code == 403) {
                HubConnectionState.AUTH_FAILED
            } else HubConnectionState.ERROR
            scheduleReconnect()
            Timber.w(t, "Hub connection failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            if (_state.value == HubConnectionState.CONNECTED) _state.value = HubConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    private fun handleMessage(message: JsonObject) {
        when (message["type"]?.jsonPrimitive?.content) {
            "main.registered" -> _state.value = HubConnectionState.CONNECTED
            "facts.snapshot" -> applySnapshot(message)
            "facts.delta" -> applyDelta(message)
            "action.result" -> {
                val requestId = message["requestId"]?.jsonPrimitive?.content.orEmpty()
                pendingActions[requestId]?.complete(HubActionResult.from(message, json))
            }
        }
    }

    private fun applySnapshot(message: JsonObject) {
        val payload = message["payload"]?.jsonObject ?: return
        val incomingVersion = message.long("factsVersion")
        synchronized(this) {
            factsSyncRequested = false
            factsRefreshWaiter?.complete(Unit)
            factsRefreshWaiter = null
        }
        if (incomingVersion < _facts.value.factsVersion) return
        val facts = HubFacts(
            factsVersion = incomingVersion,
            eventId = message.string("eventId"),
            agents = payload["agents"].decodeAgents(),
            tasks = payload["tasks"].decodeTasks(),
        )
        _facts.value = facts
        persistTasks(facts.tasks)
    }

    private fun applyDelta(message: JsonObject) {
        val old = _facts.value
        val payload = message["payload"]?.jsonObject ?: return
        val incomingVersion = message.long("factsVersion")
        when (decideHubDelta(old.factsVersion, incomingVersion)) {
            HubDeltaDecision.IGNORE -> return
            HubDeltaDecision.REQUEST_SNAPSHOT -> {
                requestFactsSnapshot("facts gap ${old.factsVersion} -> $incomingVersion")
                return
            }
            HubDeltaDecision.APPLY -> Unit
        }
        val removed = payload["removeAgents"].decodeStringList()
        val upsertAgents = payload["upsertAgents"].decodeAgents()
        val removedTasks = payload["removeTasks"].decodeStringList()
        val upsertTasks = payload["upsertTasks"].decodeTasks()
        val agents = (old.agents.filterNot { it.agentId in removed } + upsertAgents)
            .distinctBy(HubAgentFact::agentId)
        val tasks = (old.tasks.filterNot { it.taskId in removedTasks } + upsertTasks)
            .distinctBy(HubTaskFact::taskId)
        val facts = old.copy(
            factsVersion = incomingVersion,
            eventId = message.string("eventId"),
            agents = agents,
            tasks = tasks,
        )
        _facts.value = facts
        persistTasks(upsertTasks)
    }

    private fun requestFactsSnapshot(reason: String) {
        synchronized(this) {
            if (factsSyncRequested) return
            factsSyncRequested = true
        }
        val current = _facts.value
        val sent = socket?.send(buildJsonObject {
            put("type", "facts.request")
            put("lastEventId", current.eventId)
            put("factsVersion", current.factsVersion)
            put("forceSnapshot", true)
            put("reason", reason)
        }.toString()) == true
        if (!sent) synchronized(this) { factsSyncRequested = false }
        Timber.w("Requesting Hub facts snapshot: $reason")
    }

    private fun persistTasks(tasks: List<HubTaskFact>) {
        if (tasks.isEmpty()) return
        scope.launch { tasks.forEach { runCatching { taskRepository.upsertHubFact(it) } } }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val settings = currentSettings ?: return
        if (!settings.enabled || settings.token.isBlank()) return
        reconnectJob = scope.launch {
            delay(3_000L)
            connect(settings)
        }
    }

    private fun JsonElement?.decodeAgents(): List<HubAgentFact> = runCatching {
        if (this == null) emptyList() else json.decodeFromJsonElement(ListSerializer(HubAgentFact.serializer()), this)
    }.getOrDefault(emptyList())

    private fun JsonElement?.decodeTasks(): List<HubTaskFact> = runCatching {
        if (this == null) emptyList() else json.decodeFromJsonElement(ListSerializer(HubTaskFact.serializer()), this)
    }.getOrDefault(emptyList())

    private fun JsonElement?.decodeStringList(): List<String> = (this as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private companion object {
        private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
        private val JsonPrimitive.longOrNull: Long? get() = runCatching { long }.getOrNull()
    }
}

data class HubActionResult(
    val ok: Boolean,
    val requestId: String,
    val result: JsonObject = JsonObject(emptyMap()),
    val errorCode: String = "",
    val errorMessage: String = "",
) {
    companion object {
        fun from(message: JsonObject, json: Json): HubActionResult {
            val error = message["error"] as? JsonObject
            return HubActionResult(
                ok = message["ok"]?.jsonPrimitive?.booleanOrNull == true,
                requestId = message["requestId"]?.jsonPrimitive?.content.orEmpty(),
                result = message["result"] as? JsonObject ?: JsonObject(emptyMap()),
                errorCode = error?.get("code")?.jsonPrimitive?.content.orEmpty(),
                errorMessage = error?.get("message")?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }
}
