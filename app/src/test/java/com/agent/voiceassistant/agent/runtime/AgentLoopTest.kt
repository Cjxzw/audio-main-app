package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AgentLoopTest {
    @Test
    fun `returns final assistant text and emits lifecycle events`() = runBlocking {
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(message(content = "完成了")),
            ),
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(runtime, events::add).run(config())

        assertEquals(AgentLoop.Outcome.Completed("完成了", true), outcome)
        assertTrue(events.first() is AgentEvent.AgentStarted)
        assertTrue(events.any { it is AgentEvent.TurnFinished && it.finalText == "完成了" })
        assertTrue(events.last() is AgentEvent.AgentFinished)
    }

    @Test
    fun `feeds tool result back to model`() = runBlocking {
        val call = CloudSpeechClient.ToolCall("call-1", "read", "{\"path\":\"/source/a.kt\"}")
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = listOf(call)),
                    message(content = "文件已经读完"),
                ),
            ),
        )

        val outcome = AgentLoop(runtime).run(config())

        assertEquals(AgentLoop.Outcome.Completed("文件已经读完", true), outcome)
        assertEquals(listOf("call-1"), runtime.executedCalls)
        assertEquals("tool", runtime.requests[1].messages.last().role)
        assertEquals("call-1", runtime.requests[1].messages.last().toolCallId)
    }

    @Test
    fun `blocks an identical consecutive tool batch`() = runBlocking {
        val first = CloudSpeechClient.ToolCall("call-1", "read", "{\"path\":\"/logs/app.log\"}")
        val repeated = first.copy(id = "call-2")
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = listOf(first)),
                    message(toolCalls = listOf(repeated)),
                    message(content = "改用已有结果"),
                ),
            ),
        )

        AgentLoop(runtime).run(config(maxToolRounds = 3))

        assertEquals(listOf("call-1"), runtime.executedCalls)
        assertEquals(listOf("call-2"), runtime.blockedCalls)
    }

    @Test
    fun `escalation switches the current turn to deep reasoning`() = runBlocking {
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(
                        toolCalls = listOf(
                            CloudSpeechClient.ToolCall(
                                "reason-1",
                                "request_deep_reasoning",
                                "{\"reason\":\"需要比较多个方案\"}",
                            ),
                        ),
                    ),
                    message(content = "已经深入分析完成"),
                ),
            ),
        )

        val outcome = AgentLoop(runtime).run(config())

        assertEquals(AgentLoop.Outcome.Completed("已经深入分析完成", true), outcome)
        assertEquals(
            listOf(
                CloudSpeechClient.ThinkingMode.DISABLED,
                CloudSpeechClient.ThinkingMode.ENABLED,
            ),
            runtime.requests.map { it.thinkingMode },
        )
        assertEquals(listOf("需要比较多个方案"), runtime.modelEscalations)
        assertEquals("reason-1", runtime.requests[1].messages.last().toolCallId)
    }

    @Test
    fun `escalation and independent tools share a batch and run in parallel`() = runBlocking {
        val reasoning = CloudSpeechClient.ToolCall(
            "reason-1",
            "request_deep_reasoning",
            "{\"reason\":\"需要综合网络和本地资料\"}",
        )
        val search = CloudSpeechClient.ToolCall("search-1", "web_search", "{\"query\":\"示例\"}")
        val read = CloudSpeechClient.ToolCall("read-1", "read", "{\"path\":\"/source/a.kt\"}")
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = listOf(reasoning, search, read)),
                    message(content = "综合结果已经整理完成"),
                ),
            ),
            parallelToolNames = setOf("web_search", "read"),
            toolDelayMs = 20,
        )

        val outcome = AgentLoop(runtime).run(config())

        assertEquals(AgentLoop.Outcome.Completed("综合结果已经整理完成", true), outcome)
        assertTrue(runtime.maxActiveTools.get() >= 2)
        assertEquals(
            listOf("reason-1", "search-1", "read-1"),
            runtime.requests[1].messages.takeLast(3).map { it.toolCallId },
        )
        assertEquals(4_096, runtime.requests[1].maxCompletionTokens)
    }

    @Test
    fun `third business tool automatically enables thinking without extra model call`() = runBlocking {
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = listOf(CloudSpeechClient.ToolCall("call-1", "read", "{\"path\":\"/a\"}"))),
                    message(toolCalls = listOf(CloudSpeechClient.ToolCall("call-2", "read", "{\"path\":\"/b\"}"))),
                    message(toolCalls = listOf(CloudSpeechClient.ToolCall("call-3", "read", "{\"path\":\"/c\"}"))),
                    message(content = "调查完成"),
                ),
            ),
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(runtime, events::add).run(config(maxToolRounds = 4))

        assertEquals(AgentLoop.Outcome.Completed("调查完成", true), outcome)
        assertEquals(listOf("call-1", "call-2", "call-3"), runtime.executedCalls)
        assertEquals(4, runtime.requests.size)
        assertEquals(
            listOf(
                CloudSpeechClient.ThinkingMode.DISABLED,
                CloudSpeechClient.ThinkingMode.DISABLED,
                CloudSpeechClient.ThinkingMode.DISABLED,
                CloudSpeechClient.ThinkingMode.ENABLED,
            ),
            runtime.requests.map { it.thinkingMode },
        )
        assertEquals(1, runtime.automaticEscalations.size)
        assertTrue(runtime.modelEscalations.isEmpty())
        assertEquals(3, runtime.automaticEscalations.single().first)
        assertTrue(events.any { it is AgentEvent.AutomaticThinkingEscalated && it.toolCallCount == 3 })
    }

    @Test
    fun `three parallel tools escalate once and all still execute`() = runBlocking {
        val calls = listOf(
            CloudSpeechClient.ToolCall("call-1", "read", "{}"),
            CloudSpeechClient.ToolCall("call-2", "web_search", "{}"),
            CloudSpeechClient.ToolCall("call-3", "code_graph_search", "{}"),
        )
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = calls),
                    message(content = "并行调查完成"),
                ),
            ),
            parallelToolNames = calls.map { it.name }.toSet(),
        )

        val outcome = AgentLoop(runtime).run(config(maxToolRounds = 2))

        assertEquals(AgentLoop.Outcome.Completed("并行调查完成", true), outcome)
        assertEquals(calls.map { it.id }.toSet(), runtime.executedCalls.toSet())
        assertEquals(2, runtime.requests.size)
        assertEquals(CloudSpeechClient.ThinkingMode.ENABLED, runtime.requests.last().thinkingMode)
        assertEquals(1, runtime.automaticEscalations.size)
        assertEquals(calls.map { it.id }.toSet(), runtime.automaticEscalations.single().second.toSet())
    }

    @Test
    fun `parallel failures count as one failed round and model can repair`() = runBlocking {
        val failed = listOf(
            CloudSpeechClient.ToolCall("failed-1", "read", "{}"),
            CloudSpeechClient.ToolCall("failed-2", "read", "{}"),
            CloudSpeechClient.ToolCall("failed-3", "read", "{}"),
        )
        val repaired = CloudSpeechClient.ToolCall("repaired-1", "read", "{\"path\":\"/source/a.kt\"}")
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = failed),
                    message(toolCalls = listOf(repaired)),
                    message(content = "修正路径后读取成功"),
                ),
            ),
            failedCallIds = failed.map { it.id }.toSet(),
            parallelToolNames = setOf("read"),
        )

        val outcome = AgentLoop(runtime).run(config(maxToolRounds = 3))

        assertEquals(AgentLoop.Outcome.Completed("修正路径后读取成功", true), outcome)
        assertEquals(failed.map { it.id }.toSet() + repaired.id, runtime.executedCalls.toSet())
        assertEquals(3, runtime.requests.size)
    }

    @Test
    fun `reserves a tool-free final synthesis after tool budget`() = runBlocking {
        val call = CloudSpeechClient.ToolCall("call-1", "read", "{\"path\":\"/logs\"}")
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = listOf(call)),
                    message(content = "已经读取日志，这是当前结论"),
                ),
            ),
        )

        val outcome = AgentLoop(runtime).run(config(maxToolRounds = 1))

        assertEquals(AgentLoop.Outcome.Completed("已经读取日志，这是当前结论", true), outcome)
        assertEquals(2, runtime.requests.size)
        assertTrue(runtime.requests.last().tools.isEmpty())
        assertTrue(runtime.requests.last().messages.last().content.orEmpty().contains("工具阶段已经结束"))
    }

    @Test
    fun `three consecutive tool failures trigger early synthesis`() = runBlocking {
        val first = CloudSpeechClient.ToolCall("failed-1", "read", "{\"path\":\"/missing-a\"}")
        val second = CloudSpeechClient.ToolCall("failed-2", "read", "{\"path\":\"/missing-b\"}")
        val third = CloudSpeechClient.ToolCall("failed-3", "read", "{\"path\":\"/missing-c\"}")
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = listOf(first)),
                    message(toolCalls = listOf(second)),
                    message(toolCalls = listOf(third)),
                    message(content = "连续三次读取失败，我先停止重试"),
                ),
            ),
            failedCallIds = setOf("failed-1", "failed-2", "failed-3"),
        )

        val outcome = AgentLoop(runtime).run(config(maxToolRounds = 4))

        assertEquals(AgentLoop.Outcome.Completed("连续三次读取失败，我先停止重试", true), outcome)
        assertEquals(4, runtime.requests.size)
        assertTrue(runtime.requests.last().tools.isEmpty())
    }

    @Test
    fun `tool free summary rejects xml tool protocol and asks for plain text`() = runBlocking {
        val failures = listOf(
            CloudSpeechClient.ToolCall("failed-1", "read", "{}"),
            CloudSpeechClient.ToolCall("failed-2", "read", "{}"),
            CloudSpeechClient.ToolCall("failed-3", "read", "{}"),
        )
        val invalidXml = "<tool_call><function=exec><parameter=command>pwd</parameter></function></tool_call>"
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = listOf(failures[0])),
                    message(toolCalls = listOf(failures[1])),
                    message(toolCalls = listOf(failures[2])),
                    message(content = invalidXml),
                    message(content = "已有读取均失败，需要使用虚拟路径重试"),
                ),
            ),
            failedCallIds = failures.map { it.id }.toSet(),
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(runtime, events::add).run(config(maxToolRounds = 4))

        assertEquals(
            AgentLoop.Outcome.Completed("已有读取均失败，需要使用虚拟路径重试", true),
            outcome,
        )
        assertEquals(5, runtime.requests.size)
        assertTrue(runtime.requests.takeLast(2).all { it.tools.isEmpty() })
        assertTrue(
            events.filterIsInstance<AgentEvent.MessageFinished>()
                .none { it.message.content == invalidXml },
        )
    }

    @Test
    fun `details only final response is repaired with speakable summary`() = runBlocking {
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(content = "```json\n{\"status\":\"ok\"}\n```"),
                    message(content = "处理已经完成，详细数据请看手机。"),
                ),
            ),
        )

        val outcome = AgentLoop(runtime).run(config())

        assertEquals(AgentLoop.Outcome.Completed("处理已经完成，详细数据请看手机。", true), outcome)
        assertEquals(2, runtime.requests.size)
        assertTrue(runtime.requests.last().tools.isEmpty())
    }

    private fun config(maxToolRounds: Int = 2) = AgentLoop.Config(
        messages = listOf(CloudSpeechClient.LlmMessage("user", "开始")),
        initialThinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
        maxToolRounds = maxToolRounds,
        fastMaxCompletionTokens = 512,
        deepMaxCompletionTokens = 4_096,
        allowReasoningEscalation = true,
    )

    private fun message(
        content: String? = null,
        toolCalls: List<CloudSpeechClient.ToolCall> = emptyList(),
    ) = CloudSpeechClient.LlmMessage(
        role = "assistant",
        content = content,
        toolCalls = toolCalls,
    )

    private class FakeRuntime(
        private val responses: ArrayDeque<CloudSpeechClient.LlmMessage>,
        private val failedCallIds: Set<String> = emptySet(),
        private val parallelToolNames: Set<String> = emptySet(),
        private val toolDelayMs: Long = 0,
    ) : AgentLoop.Runtime {
        val requests = mutableListOf<CloudSpeechClient.ChatRequest>()
        val executedCalls = mutableListOf<String>()
        val blockedCalls = mutableListOf<String>()
        val automaticEscalations = mutableListOf<Pair<Int, List<String>>>()
        val modelEscalations = mutableListOf<String>()
        private val activeTools = AtomicInteger(0)
        val maxActiveTools = AtomicInteger(0)

        override fun toolDefinitions(allowReasoningEscalation: Boolean) = listOf(
            CloudSpeechClient.ToolDefinition("read", "read", buildJsonObject {}),
        )

        override suspend fun modelTurn(
            request: CloudSpeechClient.ChatRequest,
            beforeSpeech: suspend () -> Unit,
            onStreamEvent: (CloudSpeechClient.ChatStreamEvent) -> Unit,
        ): AgentLoop.ModelTurn {
            requests += request
            val message = responses.removeFirst()
            message.content?.let { onStreamEvent(CloudSpeechClient.ChatStreamEvent.ContentDelta(it)) }
            return AgentLoop.ModelTurn(
                CloudSpeechClient.ChatCompletion(message, "stop"),
                streamedSpeech = message.content != null,
            )
        }

        override fun normalizeAssistant(message: CloudSpeechClient.LlmMessage) = message

        override fun isReasoningEscalation(call: CloudSpeechClient.ToolCall) =
            call.name == "request_deep_reasoning"

        override fun reasoningEscalationReason(call: CloudSpeechClient.ToolCall) =
            Json.parseToJsonElement(call.arguments).jsonObject.getValue("reason").jsonPrimitive.content

        override fun onReasoningEscalation(reason: String) {
            modelEscalations += reason
        }

        override fun reasoningEscalationResult(call: CloudSpeechClient.ToolCall) =
            AgentLoop.ToolExecution(
                CloudSpeechClient.LlmMessage(
                    role = "tool",
                    content = "已启用当前用户回合的深度思考模式。",
                    toolCallId = call.id,
                ),
                succeeded = true,
            )

        override suspend fun onAutomaticReasoningEscalation(
            toolCallCount: Int,
            triggerCalls: List<CloudSpeechClient.ToolCall>,
        ) {
            automaticEscalations += toolCallCount to triggerCalls.map { it.id }
        }

        override fun canExecuteToolInParallel(call: CloudSpeechClient.ToolCall) =
            call.name in parallelToolNames

        override fun toolDisplayName(toolName: String) = toolName

        override suspend fun executeTool(call: CloudSpeechClient.ToolCall): AgentLoop.ToolExecution {
            executedCalls += call.id
            val active = activeTools.incrementAndGet()
            maxActiveTools.updateAndGet { current -> maxOf(current, active) }
            if (toolDelayMs > 0) delay(toolDelayMs)
            activeTools.decrementAndGet()
            return AgentLoop.ToolExecution(
                message = CloudSpeechClient.LlmMessage("tool", "读取结果", toolCallId = call.id),
                succeeded = call.id !in failedCallIds,
            )
        }

        override fun blockedTool(
            call: CloudSpeechClient.ToolCall,
            reason: String,
        ): CloudSpeechClient.LlmMessage {
            blockedCalls += call.id
            return CloudSpeechClient.LlmMessage("tool", reason, toolCallId = call.id)
        }

        override suspend fun finishAssistant(
            message: CloudSpeechClient.LlmMessage,
            streamedSpeech: Boolean,
        ) = true
    }
}
