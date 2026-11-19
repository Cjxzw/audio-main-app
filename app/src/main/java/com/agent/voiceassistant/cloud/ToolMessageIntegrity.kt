package com.agent.voiceassistant.cloud

/** Repairs model projections only. It never executes a tool or changes user-facing text. */
object ToolMessageIntegrity {
    class ProtocolException(message: String) : IllegalArgumentException(message)

    fun requireValid(messages: List<CloudSpeechClient.LlmMessage>) {
        val pending = linkedSetOf<String>()
        messages.forEach { message ->
            if (message.role == "tool") {
                if (!pending.remove(message.toolCallId)) throw ProtocolException("工具结果缺少对应调用，无法发送模型请求")
            } else {
                if (pending.isNotEmpty()) throw ProtocolException("工具调用缺少对应结果，无法发送模型请求")
                message.toolCalls.forEach { call ->
                    if (ToolCallSafety.invalidReason(call) != null || !pending.add(call.id)) {
                        throw ProtocolException("工具调用记录格式错误或 ID 重复，无法发送模型请求")
                    }
                }
            }
        }
        if (pending.isNotEmpty()) throw ProtocolException("工具调用尚无结果，无法发送模型请求")
    }

    fun repair(
        messages: List<CloudSpeechClient.LlmMessage>,
        recoverCall: (CloudSpeechClient.LlmMessage) -> CloudSpeechClient.ToolCall? = { null },
    ): List<CloudSpeechClient.LlmMessage> {
        val output = mutableListOf<CloudSpeechClient.LlmMessage>()
        val pending = linkedSetOf<String>()
        val seen = hashSetOf<String>()
        fun closePending() {
            pending.forEach { id -> output += CloudSpeechClient.LlmMessage(
                role = "tool", toolCallId = id,
                content = "历史工具调用未找到结果，执行状态未知。不得假定成功，也不要重复有副作用的操作；请先核验现状。",
            ) }
            pending.clear()
        }
        messages.forEach { message ->
            if (message.role == "tool") {
                if (pending.remove(message.toolCallId)) {
                    output += message
                } else {
                    closePending()
                    val call = recoverCall(message)?.takeIf {
                        it.id == message.toolCallId && it.id !in seen && ToolCallSafety.invalidReason(it) == null
                    }
                    if (call != null) {
                        output += CloudSpeechClient.LlmMessage("assistant", toolCalls = listOf(call))
                        output += message
                        seen += call.id
                    } else {
                        output += CloudSpeechClient.LlmMessage("assistant",
                            "[历史工具结果：调用记录不完整，仅供参考，不要据此重复执行动作]\n${message.content.orEmpty()}")
                    }
                }
            } else {
                closePending()
                val calls = message.toolCalls.filter { ToolCallSafety.invalidReason(it) == null }.distinctBy { it.id }
                output += if (calls == message.toolCalls) message else message.copy(toolCalls = calls)
                pending += calls.map { it.id }
                seen += pending
            }
        }
        closePending()
        requireValid(output)
        return output
    }
}
