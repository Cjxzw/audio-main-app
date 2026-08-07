package com.agent.voiceassistant.agent.runtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveTurnCheckpointStoreTest {
    @Test
    fun `checkpoint message conversion preserves resumable context`() {
        val messages = listOf(
            CloudSpeechClient.LlmMessage(
                role = "assistant",
                content = "准备读取",
                reasoningContent = "需要核对文件",
                toolCalls = listOf(
                    CloudSpeechClient.ToolCall("call-1", "read", "{\"path\":\"/source/README.md\"}"),
                ),
                attachmentPaths = listOf("/workspace/input.png"),
            ),
            CloudSpeechClient.LlmMessage(
                role = "tool",
                content = "读取完成",
                toolCallId = "call-1",
            ),
        )

        assertEquals(messages, ActiveTurnCheckpointStore.decode(ActiveTurnCheckpointStore.encode(messages)))
    }
}
