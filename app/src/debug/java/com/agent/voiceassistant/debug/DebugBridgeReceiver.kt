package com.agent.voiceassistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agent.voiceassistant.BuildConfig
import com.agent.voiceassistant.agent.LlmProviderMode
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.service.VoiceAgentService
import com.agent.voiceassistant.settings.AppCapabilityResolver
import com.agent.voiceassistant.settings.LlmProviderProfile
import com.agent.voiceassistant.settings.LlmProviderRepository
import com.agent.voiceassistant.settings.MimoApiRepository
import com.agent.voiceassistant.ui.ChatRole
import com.agent.voiceassistant.ui.ChatStreamState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class DebugBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != DEBUG_BRIDGE_ACTION) return
        val requestId = runCatching {
            DebugBridgeProtocol.requireValidRequestId(
                intent.getStringExtra(DEBUG_BRIDGE_EXTRA_REQUEST_ID).orEmpty(),
            )
        }.getOrNull() ?: return
        val appContext = context.applicationContext
        EXECUTOR.execute { process(appContext, requestId) }
    }

    private fun process(context: Context, requestId: String) {
        val root = File(context.filesDir, DEBUG_BRIDGE_DIRECTORY)
        val inbox = File(root, "inbox/$requestId.json")
        val outbox = File(root, "outbox/$requestId.json")
        val startedAt = System.currentTimeMillis()
        val response = runCatching {
            val request = DebugBridgeProtocol.decodeRequest(inbox.readText())
            require(request.request_id == requestId) { "广播与命令 request_id 不一致" }
            execute(context, request, startedAt)
        }.getOrElse { error ->
            response(
                requestId = requestId,
                ok = false,
                code = "command_failed",
                message = error.message?.take(500) ?: error.javaClass.simpleName,
                startedAt = startedAt,
            )
        }
        inbox.delete()
        writeAtomically(outbox, DebugBridgeProtocol.json.encodeToString(JsonObject.serializer(), response))
    }

    private fun execute(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
    ): JsonObject = when (request.command) {
        "status" -> success(request, "status", "Hanwo 调试状态已读取", startedAt, status(context))
        "config.show" -> success(request, "config", "配置状态已读取", startedAt, config(context))
        "key.set" -> setMimoKey(context, request, startedAt)
        "key.clear" -> clearMimoKey(context, request, startedAt)
        "provider.list" -> success(
            request,
            "providers",
            "供应商列表已读取",
            startedAt,
            buildJsonObject { put("providers", providers(context)) },
        )
        "provider.set" -> setProvider(context, request, startedAt)
        "provider.activate" -> activateProvider(context, request, startedAt)
        "provider.delete" -> deleteProvider(context, request, startedAt)
        "conversation.list" -> success(
            request,
            "conversations",
            "会话列表已读取",
            startedAt,
            buildJsonObject { put("conversations", conversations(context)) },
        )
        "conversation.new" -> newConversation(context, request, startedAt)
        "conversation.clear" -> clearConversations(context, request, startedAt)
        "agent.wake" -> serviceAction(context, request, startedAt, wake = true)
        "agent.sleep" -> serviceAction(context, request, startedAt, wake = false)
        "turn.run" -> runTurn(context, request, startedAt)
        else -> error("未知调试命令：${request.command}")
    }

    private fun status(context: Context): JsonObject {
        val store = ConversationStore(context)
        val capabilities = AppCapabilityResolver(context).capabilities()
        val active = LlmProviderRepository(context).activeProfile()
        val current = store.currentConversationSummary()
        return buildJsonObject {
            put("package", context.packageName)
            put("version_name", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("git_commit", BuildConfig.GIT_COMMIT)
            put("debug", BuildConfig.DEBUG)
            put("llm_available", capabilities.llmAvailable)
            put("speech_available", capabilities.speechAvailable)
            put("active_provider", provider(context, active))
            putJsonObject("current_conversation") {
                put("id", current.id)
                put("title", current.title)
                put("message_count", current.messageCount)
            }
        }
    }

    private fun config(context: Context): JsonObject {
        val mimo = MimoApiRepository(context)
        val repositories = LlmProviderRepository(context)
        return buildJsonObject {
            put("mimo_key_configured", mimo.hasValidKey())
            put("mimo_key_type", mimo.keyType()?.name?.lowercase())
            put("mimo_key_fingerprint", mimo.apiKey().takeIf(String::isNotBlank)?.let(::fingerprint))
            put("active_provider_id", repositories.activeProfile().id)
            put("providers", providers(context))
        }
    }

    private fun setMimoKey(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
    ): JsonObject {
        val key = request.arguments.string("api_key") ?: error("缺少 api_key")
        val repository = MimoApiRepository(context)
        repository.saveKey(key)
        return success(
            request,
            "key_configured",
            "MiMo Key 已写入加密存储",
            startedAt,
            buildJsonObject {
                put("key_type", repository.keyType()?.name?.lowercase())
                put("fingerprint", fingerprint(repository.apiKey()))
            },
        )
    }

    private fun clearMimoKey(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
    ): JsonObject {
        require(request.arguments.boolean("confirm") == true) { "清除 Key 必须确认" }
        MimoApiRepository(context).clearKey()
        return success(request, "key_cleared", "MiMo Key 已清除", startedAt)
    }

    private fun setProvider(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
    ): JsonObject {
        val args = request.arguments
        val repository = LlmProviderRepository(context)
        val id = args.string("id")
        val existing = repository.profile(id)
        val apiKey = args.string("api_key") ?: if (existing?.let { repository.hasApiKey(it.id) } == true) {
            null
        } else {
            MimoApiRepository(context).apiKey().takeIf(String::isNotBlank)
        }
        val profile = repository.save(
            id = id,
            displayName = args.string("name") ?: existing?.displayName ?: error("缺少 name"),
            baseUrl = args.string("base_url") ?: existing?.baseUrl ?: error("缺少 base_url"),
            modelId = args.string("model") ?: existing?.modelId ?: error("缺少 model"),
            mode = args.string("mode")?.let(::parseMode) ?: existing?.mode ?: LlmProviderMode.MIMO,
            apiKey = apiKey,
            supportsImages = args.boolean("supports_images") ?: existing?.supportsImages ?: false,
        )
        if (args.boolean("activate") != false) repository.setActive(profile.id)
        return success(
            request,
            "provider_configured",
            "供应商已配置${if (repository.activeProfile().id == profile.id) "并启用" else ""}",
            startedAt,
            provider(context, profile),
        )
    }

    private fun activateProvider(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
    ): JsonObject {
        val id = request.arguments.string("id") ?: error("缺少 id")
        val repository = LlmProviderRepository(context)
        repository.setActive(id)
        return success(
            request,
            "provider_activated",
            "供应商已启用",
            startedAt,
            provider(context, repository.activeProfile()),
        )
    }

    private fun deleteProvider(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
    ): JsonObject {
        require(request.arguments.boolean("confirm") == true) { "删除供应商必须确认" }
        val id = request.arguments.string("id") ?: error("缺少 id")
        require(id != LlmProviderRepository.BUILT_IN_ID) { "不能删除内置 MiMo 供应商" }
        val repository = LlmProviderRepository(context)
        require(repository.profile(id) != null) { "供应商不存在" }
        repository.delete(id)
        return success(
            request,
            "provider_deleted",
            "供应商已删除",
            startedAt,
            buildJsonObject {
                put("id", id)
                put("active_provider_id", repository.activeProfile().id)
            },
        )
    }

    private fun newConversation(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
    ): JsonObject {
        val store = ConversationStore(context)
        val session = store.startNewConversation("agent debug cli")
        EventBus.emitChatReset(emptyList())
        EventBus.emitConversationUpdate()
        return success(
            request,
            "conversation_created",
            "新会话已创建",
            startedAt,
            buildJsonObject { put("id", session.id) },
        )
    }

    private fun clearConversations(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
    ): JsonObject {
        require(request.arguments.boolean("confirm") == true) { "清空会话必须确认" }
        val store = ConversationStore(context)
        val deleted = store.conversationSummaries().count { store.deleteConversation(it.id) }
        EventBus.emitChatReset(store.recentChatMessages())
        EventBus.emitConversationUpdate()
        return success(
            request,
            "conversations_cleared",
            "会话已清空",
            startedAt,
            buildJsonObject {
                put("deleted", deleted)
                put("current_conversation_id", store.currentConversationId)
            },
        )
    }

    private fun serviceAction(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
        wake: Boolean,
    ): JsonObject {
        if (wake) VoiceAgentService.wake(context) else VoiceAgentService.sleep(context)
        return success(
            request,
            if (wake) "wake_requested" else "sleep_requested",
            if (wake) "已请求唤醒" else "已请求休眠",
            startedAt,
        )
    }

    private fun runTurn(
        context: Context,
        request: DebugBridgeRequest,
        startedAt: Long,
    ): JsonObject {
        val text = request.arguments.string("text") ?: error("缺少 text")
        val timeoutMs = (request.arguments.long("timeout_ms") ?: DEFAULT_TURN_TIMEOUT_MS)
            .coerceIn(MIN_TURN_TIMEOUT_MS, MAX_TURN_TIMEOUT_MS)
        val store = ConversationStore(context)
        val baselineIds = store.recentChatMessages().mapNotNullTo(hashSetOf()) { it.messageId }
        val baselineHistorySize = store.llmHistory().size
        VoiceAgentService.sendText(context, text)

        val deadline = System.currentTimeMillis() + timeoutMs
        var userTimestamp: Long? = null
        var assistant = store.recentChatMessages().lastOrNull()
        while (System.currentTimeMillis() < deadline) {
            val messages = store.recentChatMessages()
            if (userTimestamp == null) {
                userTimestamp = messages.firstOrNull { message ->
                    message.messageId !in baselineIds && message.role == ChatRole.USER && message.text == text
                }?.timestamp
            }
            assistant = userTimestamp?.let { timestamp ->
                messages.lastOrNull { message ->
                    message.messageId !in baselineIds &&
                        message.role == ChatRole.BOT &&
                        message.timestamp >= timestamp &&
                        message.streamState != ChatStreamState.STREAMING
                }
            }
            if (assistant != null && userTimestamp != null) break
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val finalMessage = assistant?.takeIf { userTimestamp != null }
            ?: error("等待 Agent 回复超时（${timeoutMs}ms）")
        val newChatMessages = store.recentChatMessages().filter { it.messageId !in baselineIds }
        val modelMessages = store.llmHistory().drop(baselineHistorySize)
        return success(
            request,
            "turn_completed",
            "Agent 回合已完成",
            startedAt,
            buildJsonObject {
                put("conversation_id", store.currentConversationId)
                put("assistant_text", finalMessage.text)
                put("chat_messages", buildJsonArray {
                    newChatMessages.forEach { message ->
                        add(buildJsonObject {
                            put("role", message.role.name.lowercase())
                            put("text", message.text)
                            put("timestamp", message.timestamp)
                            put("stream_state", message.streamState?.name?.lowercase())
                        })
                    }
                })
                put("model_messages", buildJsonArray {
                    modelMessages.forEach { message ->
                        add(buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                            put("tool_call_id", message.toolCallId)
                            put("tool_calls", buildJsonArray {
                                message.toolCalls.forEach { call ->
                                    add(buildJsonObject {
                                        put("id", call.id)
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    })
                                }
                            })
                        })
                    }
                })
            },
        )
    }

    private fun providers(context: Context): JsonArray {
        val repository = LlmProviderRepository(context)
        return buildJsonArray {
            repository.profiles().forEach { add(provider(context, it)) }
        }
    }

    private fun provider(context: Context, profile: LlmProviderProfile): JsonObject {
        val repository = LlmProviderRepository(context)
        return buildJsonObject {
            put("id", profile.id)
            put("name", profile.displayName)
            put("base_url", profile.baseUrl)
            put("model", profile.modelId)
            put("mode", profile.mode.name.lowercase())
            put("built_in", profile.builtIn)
            put("supports_images", profile.supportsImages)
            put("has_api_key", repository.hasApiKey(profile.id))
            put("active", repository.activeProfile().id == profile.id)
        }
    }

    private fun conversations(context: Context): JsonArray = buildJsonArray {
        ConversationStore(context).conversationSummaries().forEach { summary ->
            add(buildJsonObject {
                put("id", summary.id)
                put("title", summary.title)
                put("preview", summary.preview)
                put("message_count", summary.messageCount)
                put("created_at", summary.createdAt)
                put("updated_at", summary.updatedAt)
                put("current", summary.current)
            })
        }
    }

    private fun success(
        request: DebugBridgeRequest,
        code: String,
        message: String,
        startedAt: Long,
        data: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = response(request.request_id, true, code, message, startedAt, data)

    private fun response(
        requestId: String,
        ok: Boolean,
        code: String,
        message: String,
        startedAt: Long,
        data: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = buildJsonObject {
        put("version", 1)
        put("request_id", requestId)
        put("ok", ok)
        put("code", code)
        put("message", message)
        put("duration_ms", (System.currentTimeMillis() - startedAt).coerceAtLeast(0))
        put("data", data)
    }

    private fun writeAtomically(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(content)
        check(temporary.renameTo(file)) { "调试结果写入失败" }
    }

    private fun parseMode(value: String): LlmProviderMode = when (value.lowercase()) {
        "mimo" -> LlmProviderMode.MIMO
        "openai", "openai_compatible", "openai-compatible" -> LlmProviderMode.OPENAI_COMPATIBLE
        else -> error("不支持的供应商协议：$value")
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private companion object {
        val EXECUTOR = Executors.newSingleThreadExecutor { task ->
            Thread(task, "hanwo-debug-bridge").apply { isDaemon = true }
        }
        const val DEFAULT_TURN_TIMEOUT_MS = 120_000L
        const val MIN_TURN_TIMEOUT_MS = 1_000L
        const val MAX_TURN_TIMEOUT_MS = 300_000L
        const val POLL_INTERVAL_MS = 200L
    }
}
