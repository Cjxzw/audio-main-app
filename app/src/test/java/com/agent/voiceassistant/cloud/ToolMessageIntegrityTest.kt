package com.agent.voiceassistant.cloud

import org.junit.Assert.*
import org.junit.Test

class ToolMessageIntegrityTest {
    private fun call(id: String) = CloudSpeechClient.ToolCall(id, "skill_edit", "{}")
    private fun header(vararg ids: String) = CloudSpeechClient.LlmMessage("assistant", toolCalls = ids.map(::call))
    private fun result(id: String) = CloudSpeechClient.LlmMessage("tool", "success-$id", toolCallId = id)

    @Test fun validParallelResultsRemainUnchanged() {
        val messages = listOf(header("a", "b"), result("b"), result("a"), CloudSpeechClient.LlmMessage("user", "下一轮"))
        ToolMessageIntegrity.requireValid(messages)
        assertEquals(messages, ToolMessageIntegrity.repair(messages))
    }
    @Test fun auditRecoversMissingCallWithoutExecutingIt() {
        val messages = listOf(CloudSpeechClient.LlmMessage("user", "编辑"), result("a"))
        val fixed = ToolMessageIntegrity.repair(messages) { call("a") }
        assertEquals(listOf(messages.first(), header("a"), result("a")), fixed)
        assertEquals(fixed, ToolMessageIntegrity.repair(fixed))
    }
    @Test fun unknownAndDuplicateResultsRemainAsReferenceText() {
        val fixed = ToolMessageIntegrity.repair(listOf(header("a"), result("a"), result("a"), result("unknown"))) { call("a") }
        ToolMessageIntegrity.requireValid(fixed)
        assertEquals(1, fixed.count { it.role == "tool" })
        assertTrue(fixed.last().content!!.contains("success-unknown"))
        assertTrue(fixed.last().content!!.contains("不要据此重复执行"))
    }
    @Test fun unfinishedCallsAreClosedBeforeNewUserMessages() {
        val user = CloudSpeechClient.LlmMessage("user", "已取消，不要再执行")
        val fixed = ToolMessageIntegrity.repair(listOf(header("a", "b"), result("a"), user))
        assertEquals("b", fixed[2].toolCallId)
        assertTrue(fixed[2].content!!.contains("执行状态未知"))
        assertSame(user, fixed.last())
        ToolMessageIntegrity.requireValid(fixed)
    }
    @Test fun malformedAndDuplicateHeadersCannotBreakProjection() {
        val malformed = CloudSpeechClient.ToolCall("broken", "skill_edit", "not json")
        val messages = listOf(CloudSpeechClient.LlmMessage("assistant", toolCalls = listOf(call("a"), call("a"), malformed)), result("a"), result("broken"))
        ToolMessageIntegrity.requireValid(ToolMessageIntegrity.repair(messages))
    }
    @Test fun validationRejectsOrphansMissingResultsAndDuplicates() {
        for (messages in listOf(listOf(result("a")), listOf(header("a")), listOf(header("a", "a"), result("a")), listOf(header("a"), CloudSpeechClient.LlmMessage("user", "插入"), result("a")))) {
            assertThrows(ToolMessageIntegrity.ProtocolException::class.java) { ToolMessageIntegrity.requireValid(messages) }
        }
    }
}
