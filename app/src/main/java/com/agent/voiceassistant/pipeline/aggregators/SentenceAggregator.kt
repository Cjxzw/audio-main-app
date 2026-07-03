package com.agent.voiceassistant.pipeline.aggregators

/**
 * SentenceAggregator：把流式 LLM 输出聚合成完整句子。
 *
 * 规则（参考 Pipecat sentence.py）：
 * - 遇到句号/感叹号/问号（。！？.!?）即认为一句结束
 * - 换行符 \n 也作为句子分隔
 * - 句子长度超过 [maxChars]（如 80 字）即使无标点也强制截断，避免 TTS 等待过久
 * - flush() 在 LLM 响应结束时调用，输出剩余累积内容
 */
class SentenceAggregator(
    private val maxChars: Int = 80
) {
    private val buffer = StringBuilder()

    /**
     * 输入一段文本（可能是 LLM 流式片段），返回所有已完成的句子列表。
     * @return 完成的句子列表（可能为空）
     */
    fun feed(text: String): List<String> {
        val sentences = mutableListOf<String>()
        for (ch in text) {
            buffer.append(ch)
            if (isSentenceEnd(ch) || buffer.length >= maxChars) {
                val s = buffer.toString().trim()
                if (s.isNotEmpty()) sentences.add(s)
                buffer.clear()
            }
        }
        return sentences
    }

    /** 输出剩余的累积内容（LLM 响应结束时调用） */
    fun flush(): String? {
        if (buffer.isEmpty()) return null
        val s = buffer.toString().trim()
        buffer.clear()
        return s.takeIf { it.isNotEmpty() }
    }

    /** 重置内部状态（清空累积） */
    fun reset() {
        buffer.clear()
    }

    private fun isSentenceEnd(ch: Char): Boolean = when (ch) {
        '。', '！', '？', '.', '!', '?', '\n', '\r' -> true
        else -> false
    }
}
