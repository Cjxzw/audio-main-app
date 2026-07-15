package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `returns escalation only on first fast model call`() = runBlocking {
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
                ),
            ),
        )

        val outcome = AgentLoop(runtime).run(config())

        assertEquals(AgentLoop.Outcome.Escalate("需要比较多个方案"), outcome)
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
    fun `two consecutive tool failures trigger early synthesis`() = runBlocking {
        val first = CloudSpeechClient.ToolCall("failed-1", "read", "{\"path\":\"/missing-a\"}")
        val second = CloudSpeechClient.ToolCall("failed-2", "read", "{\"path\":\"/missing-b\"}")
        val runtime = FakeRuntime(
            responses = ArrayDeque(
                listOf(
                    message(toolCalls = listOf(first)),
                    message(toolCalls = listOf(second)),
                    message(content = "连续两次读取失败，我先停止重试"),
                ),
            ),
            failedCallIds = setOf("failed-1", "failed-2"),
        )

        val outcome = AgentLoop(runtime).run(config(maxToolRounds = 3))

        assertEquals(AgentLoop.Outcome.Completed("连续两次读取失败，我先停止重试", true), outcome)
        assertEquals(3, runtime.requests.size)
        assertTrue(runtime.requests.last().tools.isEmpty())
    }

    private fun config(maxToolRounds: Int = 2) = AgentLoop.Config(
        messages = listOf(CloudSpeechClient.LlmMessage("user", "开始")),
        thinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
        maxToolRounds = maxToolRounds,
        maxCompletionTokens = 512,
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
    ) : AgentLoop.Runtime {
        val requests = mutableListOf<CloudSpeechClient.ChatRequest>()
        val executedCalls = mutableListOf<String>()
        val blockedCalls = mutableListOf<String>()

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

        override fun toolDisplayName(toolName: String) = toolName

        override suspend fun executeTool(call: CloudSpeechClient.ToolCall): AgentLoop.ToolExecution {
            executedCalls += call.id
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
