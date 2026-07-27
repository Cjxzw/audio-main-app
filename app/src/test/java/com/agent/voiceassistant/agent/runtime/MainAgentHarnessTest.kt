package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainAgentHarnessTest {
    @Test
    fun `steering and follow ups remain separate fifo queues`() {
        val harness = MainAgentHarness()

        harness.steer("先检查日志")
        harness.steer(" 再看配置 ")
        harness.followUp("最后生成报告")

        assertEquals(listOf("先检查日志", "再看配置"), harness.drainSteering().map { it.text })
        assertEquals(listOf("最后生成报告"), harness.drainFollowUps().map { it.text })
        assertTrue(harness.drainSteering().isEmpty())
    }

    @Test
    fun `abort cancels only active turn and returns to idle`() = runBlocking {
        val enteredModel = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val runtime = object : AgentLoop.Runtime by NoopRuntime() {
            override suspend fun modelTurn(
                request: CloudSpeechClient.ChatRequest,
                beforeSpeech: suspend () -> Unit,
                onStreamEvent: (CloudSpeechClient.ChatStreamEvent) -> Unit,
            ): AgentLoop.ModelTurn {
                enteredModel.complete(Unit)
                neverCompletes.await()
                error("unreachable")
            }
        }
        val harness = MainAgentHarness()
        val running = async {
            runCatching { harness.run(AgentLoop(runtime), config()) }
        }
        enteredModel.await()

        assertEquals(MainAgentHarness.State.RUNNING, harness.state.value)
        assertTrue(harness.abort("test"))
        running.await()
        yield()
        assertEquals(MainAgentHarness.State.IDLE, harness.state.value)
        assertFalse(harness.abort())
    }

    private fun config() = AgentLoop.Config(
        messages = listOf(CloudSpeechClient.LlmMessage("user", "test")),
        initialThinkingMode = CloudSpeechClient.ThinkingMode.DISABLED,
        maxToolRounds = 1,
        fastMaxCompletionTokens = 32,
        deepMaxCompletionTokens = 64,
        allowReasoningEscalation = false,
    )

    private open class NoopRuntime : AgentLoop.Runtime {
        override fun toolDefinitions(allowReasoningEscalation: Boolean) = emptyList<CloudSpeechClient.ToolDefinition>()

        override suspend fun modelTurn(
            request: CloudSpeechClient.ChatRequest,
            beforeSpeech: suspend () -> Unit,
            onStreamEvent: (CloudSpeechClient.ChatStreamEvent) -> Unit,
        ) = AgentLoop.ModelTurn(
            CloudSpeechClient.ChatCompletion(CloudSpeechClient.LlmMessage("assistant", "ok"), "stop"),
            true,
        )

        override fun normalizeAssistant(message: CloudSpeechClient.LlmMessage) = message
        override fun isReasoningEscalation(call: CloudSpeechClient.ToolCall) = false
        override fun reasoningEscalationReason(call: CloudSpeechClient.ToolCall) = ""
        override fun reasoningEscalationResult(call: CloudSpeechClient.ToolCall) = error("unused")
        override fun canExecuteToolInParallel(call: CloudSpeechClient.ToolCall) = false
        override fun toolDisplayName(toolName: String) = toolName
        override suspend fun executeTool(call: CloudSpeechClient.ToolCall): AgentLoop.ToolExecution = error("unused")
        override fun blockedTool(call: CloudSpeechClient.ToolCall, reason: String) = error("unused")
        override suspend fun finishAssistant(
            turnId: String,
            message: CloudSpeechClient.LlmMessage,
            streamedSpeech: Boolean,
        ) = true
    }
}
