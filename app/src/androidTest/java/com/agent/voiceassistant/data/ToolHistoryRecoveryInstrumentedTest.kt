package com.agent.voiceassistant.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.agent.LLMConfig
import com.agent.voiceassistant.agent.runtime.ActiveTurnCheckpointStore
import com.agent.voiceassistant.agent.runtime.SkillApprovalStore
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.CloudSpeechClient.LlmMessage
import com.agent.voiceassistant.cloud.CloudSpeechClient.ToolCall
import com.agent.voiceassistant.cloud.OpenAiCompatibleLlmClient
import com.agent.voiceassistant.cloud.ToolMessageIntegrity
import com.agent.voiceassistant.tools.LocalToolExecutor
import com.agent.voiceassistant.tools.MainToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** All mutations are confined to unique cache directories, including imported upgrade fixtures. */
@RunWith(AndroidJUnit4::class)
class ToolHistoryRecoveryInstrumentedTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val directories = mutableListOf<File>()
    private val call = ToolCall("edit-1", "skill_edit", "{\"operation\":\"rename\"}")

    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "tool-history-${UUID.randomUUID()}").apply { mkdirs() }
        directories += directory
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
    }

    @After fun cleanup() { directories.forEach { it.deleteRecursively() } }

    private fun seed(context: Context, messages: List<StoredMessage>) {
        File(context.filesDir, "main-agent-store.json").writeText(json.encodeToString(StoreState(
            currentConversationId = "legacy",
            sessions = mutableListOf(ConversationSession("legacy", updatedAt = 1234L, messages = messages.toMutableList())),
        )))
    }

    private fun persisted(context: Context): StoreState = json.decodeFromString(
        File(context.filesDir, "main-agent-store.json").readText(),
    )

    private fun trace(context: Context, toolCall: ToolCall = call, result: String = "修改提案已提交", turn: String = "turn") {
        File(context.filesDir, "agent-runtime/tool-traces/$turn/${toolCall.id}.json").apply {
            parentFile!!.mkdirs()
            writeText(json.encodeToString(StoredToolTrace(turn, toolCall.id, toolCall.name, toolCall.arguments, result, true, 1200L)))
        }
    }

    private fun orphan() = listOf(
        StoredMessage("user", "user", "修改这个技能", timestamp = 1100L),
        StoredMessage("result", "tool", "修改提案已提交", timestamp = 1200L,
            toolCallId = call.id, llmVisible = true, chatVisible = false),
        StoredMessage("reply", "assistant", "已提交，请确认", timestamp = 1234L),
        StoredMessage("next", "user", "继续", timestamp = 1300L),
    )

    @Test fun restoresExactTraceAndPreservesDisplayActivityAndDiskAcrossReload() {
        val context = context()
        val original = orphan()
        seed(context, original)
        trace(context)
        val store = ConversationStore(context)
        val history = store.llmHistory()
        ToolMessageIntegrity.requireValid(history)
        assertEquals(call, history.single { it.toolCalls.isNotEmpty() }.toolCalls.single())
        assertEquals(original.filter { it.chatVisible != false }.map { it.content }, store.recentChatMessages().map { it.text })
        assertEquals(1234L, store.currentConversationSummary().updatedAt)
        val state = persisted(context)
        assertEquals("legacy", state.currentConversationId)
        assertEquals(original, state.sessions.single().messages.filter { it.id in original.map { message -> message.id } })
        val disk = File(context.filesDir, "main-agent-store.json").readText()
        ConversationStore(context)
        assertEquals(disk, File(context.filesDir, "main-agent-store.json").readText())
        // A new directory bypasses the in-process shared-store cache, simulating an App restart.
        val restarted = context()
        File(restarted.filesDir, "main-agent-store.json").writeText(disk)
        assertEquals(history, ConversationStore(restarted).llmHistory())
    }

    @Test fun absentMismatchedAndAmbiguousTracesBecomeReferencesWithoutDeletingOriginals() {
        repeat(3) { mode ->
            val context = context()
            seed(context, orphan())
            if (mode == 1) trace(context, result = "不匹配的结果")
            if (mode == 2) { trace(context); trace(context, turn = "another-turn") }
            val store = ConversationStore(context)
            val history = store.llmHistory()
            ToolMessageIntegrity.requireValid(history)
            assertTrue(history.none { it.role == "tool" || it.toolCalls.isNotEmpty() })
            assertTrue(history.any { it.content.orEmpty().contains("修改提案已提交") })
            val raw = persisted(context).sessions.single().messages.single { it.id == "result" }
            assertEquals("修改提案已提交", raw.content)
            assertEquals(false, raw.llmVisible)
            assertEquals(1234L, store.currentConversationSummary().updatedAt)
        }
    }

    @Test fun interruptedParallelCallIsClosedBeforeNextUserAndNotMarkedSuccessful() {
        val context = context()
        val other = call.copy(id = "edit-2")
        seed(context, listOf(
            StoredMessage("header", "assistant", "", timestamp = 1000L,
                toolCalls = listOf(call, other).map { StoredToolCall(it.id, it.name, it.arguments) },
                llmVisible = true, chatVisible = false),
            StoredMessage("result", "tool", "已提交", timestamp = 1100L, toolCallId = call.id, llmVisible = true, chatVisible = false),
            StoredMessage("user", "user", "继续", timestamp = 1200L),
        ))
        val history = ConversationStore(context).llmHistory()
        ToolMessageIntegrity.requireValid(history)
        assertEquals(listOf("assistant", "tool", "tool", "user"), history.map { it.role })
        assertEquals(other.id, history[2].toolCallId)
        assertTrue(history[2].content.orEmpty().contains("执行状态未知"))
        assertTrue(history[2].content.orEmpty().contains("不要重复"))
    }

    @Test fun resultWriterRecoversMissedHeaderAndDoesNotReuseCompletedHeader() {
        val context = context()
        val store = ConversationStore(context)
        val result = LlmMessage("tool", "结果", toolCallId = call.id)
        store.addToolResult("first", call, result, true)
        store.addMessage("user", "再试一次")
        store.addToolResult("second", call, result, true)
        val history = store.llmHistory()
        ToolMessageIntegrity.requireValid(history)
        assertEquals(2, history.count { it.toolCalls.any { tool -> tool.id == call.id } })
        assertEquals(2, history.count { it.role == "tool" })
        assertEquals(5, history.size)
    }

    @Test fun checkpointRecoveryIsDurableAndPreservesPhaseAndTimestamp() {
        val context = context()
        trace(context)
        val checkpoint = ActiveTurnCheckpointStore(context)
        val snapshot = ActiveTurnCheckpointStore.Snapshot(
            turnId = "turn", conversationId = "legacy", source = "text", speakReplies = false,
            userRequest = "继续", phase = "WAITING_RECOVERY",
            messages = ActiveTurnCheckpointStore.encode(listOf(LlmMessage("user", "修改"),
                LlmMessage("tool", "修改提案已提交", toolCallId = call.id))),
            thinkingEnabled = false, businessToolCallCount = 1, activeElapsedMs = 10L,
            activeBudgetStarted = true, updatedAt = 1234L,
        )
        checkpoint.save(snapshot)
        val restored = checkpoint.load()!!
        ToolMessageIntegrity.requireValid(ActiveTurnCheckpointStore.decode(restored.messages))
        assertEquals(call, ActiveTurnCheckpointStore.decode(restored.messages)[1].toolCalls.single())
        assertEquals("WAITING_RECOVERY", restored.phase)
        assertEquals(1234L, restored.updatedAt)
        assertEquals(restored, ActiveTurnCheckpointStore(context).load())
        checkpoint.save(snapshot.copy(phase = "RUNNING", messages = ActiveTurnCheckpointStore.encode(
            listOf(LlmMessage("assistant", toolCalls = listOf(call))))))
        val interrupted = ActiveTurnCheckpointStore.decode(checkpoint.load()!!.messages)
        ToolMessageIntegrity.requireValid(interrupted)
        assertTrue(interrupted.last().content.orEmpty().contains("执行状态未知"))
    }

    @Test fun actualSkillEditPersistsPairedCallAndNextRequestPassesHttpProtocolValidation() = runBlocking {
        val context = context()
        val store = ConversationStore(context)
        val skills = SkillRegistry(File(context.filesDir, "skills"))
        val skill = skills.create("测试技能", "test", "# 测试")
        val approvals = SkillApprovalStore(File(context.filesDir, "approvals"),
            File(context.filesDir, "workspace").apply { mkdirs() }, skills)
        val registry = MainToolRegistry(LocalToolExecutor(store, skillRegistry = skills, skillApprovals = approvals))
        MainToolRegistry.Profile.entries.forEach { profile ->
            registry.definitions(profile, false).forEach { assertTrue(it.name, registry.isNativeTool(it.name)) }
        }
        assertFalse(registry.shouldPersistToolCall(ToolCall("read-skill", "skill_use", "{}")))
        assertTrue(registry.shouldPersistToolCall(ToolCall("run-skill", "skill_use", "{\"mode\":\"run\"}")))
        assertFalse(registry.shouldPersistToolCall(ToolCall("unknown", "unknown", "{}")))
        val edit = call.copy(arguments = buildJsonObject {
            put("skill_name", skill.name); put("operation", "rename")
            put("expected_sha256", skill.version); put("new_text", "测试技能改名")
        }.toString())
        store.addMessage("user", "改名")
        assertTrue(registry.shouldPersistToolCall(edit))
        store.addLlmMessage(LlmMessage("assistant", toolCalls = listOf(edit).filter(registry::shouldPersistToolCall)))
        val execution = registry.execute(edit)
        assertTrue(execution.result.contextText, execution.result.success)
        assertNotNull(approvals.pending.value)
        assertEquals("测试技能", skills.listAll().single().name) // It remains a proposal, never auto-approved.
        store.addToolResult("turn", edit, LlmMessage("tool", execution.result.contextText, toolCallId = edit.id), true)
        store.addMessage("assistant", "已提交改名提案")
        store.addMessage("user", "现在的状态是什么？")
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val received = FutureTask {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream().buffered()
                    fun line(): String = buildString {
                        while (true) { val byte = input.read(); check(byte >= 0); if (byte == 10) break; if (byte != 13) append(byte.toChar()) }
                    }
                    assertTrue(line().startsWith("POST "))
                    var size = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                    }
                    val bytes = ByteArray(size)
                    var offset = 0
                    while (offset < size) { val count = input.read(bytes, offset, size - offset); check(count > 0); offset += count }
                    val messages = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("messages").jsonArray
                    // Independent wire-level check, rather than trusting the production normalizer.
                    val pending = mutableSetOf<String>()
                    var valid = true
                    messages.forEach { item ->
                        val message = item.jsonObject
                        if (message.getValue("role").jsonPrimitive.content == "tool") {
                            valid = pending.remove(message.getValue("tool_call_id").jsonPrimitive.content) && valid
                        } else {
                            valid = pending.isEmpty() && valid
                            message["tool_calls"]?.jsonArray?.forEach { tool ->
                                valid = pending.add(tool.jsonObject.getValue("id").jsonPrimitive.content) && valid
                            }
                        }
                    }
                    valid = pending.isEmpty() && valid
                    val response = if (valid) "data: {\"choices\":[{\"delta\":{\"content\":\"下一轮正常\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        else "{\"error\":\"orphan tool result\"}"
                    val body = response.toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                    assertTrue(valid)
                    messages
                }
            }
            Thread(received, "tool-history-http-test").start()
            val client = OpenAiCompatibleLlmClient(LLMConfig("test-only", "http://127.0.0.1:${server.localPort}", "test"))
            try {
                val response = client.streamChat(CloudSpeechClient.ChatRequest(store.llmHistory(), emptyList(),
                    CloudSpeechClient.ThinkingMode.DISABLED)) {}
                assertEquals("下一轮正常", response.message.content)
                assertEquals(1, received.get(10, TimeUnit.SECONDS).count { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" })
            } finally { client.close() }
        }
    }

    @Test fun importedUpgradeFixturePreservesEveryDisplayedMessageAndRepairsAllSessions() {
        val path = InstrumentationRegistry.getArguments().getString("upgradeFixture")
        assumeTrue("Optional private upgrade fixture is supplied by the local test runner", !path.isNullOrBlank())
        val context = context()
        File(path!!).copyRecursively(context.filesDir, overwrite = true)
        val before = persisted(context)
        val store = ConversationStore(context)
        val after = persisted(context)
        assertEquals(before.currentConversationId, after.currentConversationId)
        assertEquals(before.memories, after.memories)
        before.sessions.forEach { session ->
            val repaired = after.sessions.single { it.id == session.id }
            assertEquals(session.updatedAt, repaired.updatedAt)
            assertEquals(session.messages.filter { it.chatVisible != false }, repaired.messages.filter { it.chatVisible != false })
            ToolMessageIntegrity.requireValid(store.llmHistoryForConversation(session.id))
        }
        val audit = File(context.filesDir, "agent-runtime/tool-traces").walkTopDown().first { it.isFile && it.extension == "json" }
            .let { json.decodeFromString<StoredToolTrace>(it.readText()) }
        assertTrue(store.llmHistoryForConversation(before.currentConversationId).any { message ->
            message.toolCalls.any { it == ToolCall(audit.toolCallId, audit.toolName, audit.arguments) }
        })
        val checkpoint = ActiveTurnCheckpointStore(context).load()!!
        ToolMessageIntegrity.requireValid(ActiveTurnCheckpointStore.decode(checkpoint.messages))
        assertEquals("WAITING_RECOVERY", checkpoint.phase)
    }
}
