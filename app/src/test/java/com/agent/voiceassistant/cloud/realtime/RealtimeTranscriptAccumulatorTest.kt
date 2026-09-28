package com.agent.voiceassistant.cloud.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeTranscriptAccumulatorTest {
    @Test
    fun `does not duplicate repeated complete transcript snapshots`() {
        assertEquals("喂喂喂", RealtimeTranscriptAccumulator.merge("喂喂喂", "喂喂喂"))
        assertEquals("喂喂喂", RealtimeTranscriptAccumulator.merge("喂", "喂喂喂"))
    }

    @Test
    fun `appends actual suffix deltas once`() {
        assertEquals("今天天气", RealtimeTranscriptAccumulator.merge("今天", "天气"))
        assertEquals("你好世界", RealtimeTranscriptAccumulator.merge("你好", "世界"))
    }
}
