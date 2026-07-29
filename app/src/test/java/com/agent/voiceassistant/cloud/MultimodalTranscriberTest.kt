package com.agent.voiceassistant.cloud

import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalTranscriberTest {
    @Test
    fun promptPreservesEvidenceBoundaries() {
        val prompt = MultimodalTranscriber.TRANSCRIPTION_PROMPT
        assertTrue(prompt.contains("不直接回答用户问题"))
        assertTrue(prompt.contains("最近三轮"))
        assertTrue(prompt.contains("空间位置"))
        assertTrue(prompt.contains("不是用户原文"))
        assertTrue(prompt.contains("无法识别"))
    }
}
