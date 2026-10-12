package com.agent.voiceassistant.cloud.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeTranscriptAccumulatorTest {
    @Test
    fun `appends repeated characters because delta is not a snapshot`() {
        assertEquals("喂喂喂喂喂喂", RealtimeTranscriptAccumulator.appendDelta("喂喂喂", "喂喂喂"))
        assertEquals("喂喂喂喂", RealtimeTranscriptAccumulator.appendDelta("喂", "喂喂喂"))
    }

    @Test
    fun `appends actual suffix deltas once`() {
        assertEquals("今天天气", RealtimeTranscriptAccumulator.appendDelta("今天", "天气"))
        assertEquals("你好世界", RealtimeTranscriptAccumulator.appendDelta("你好", "世界"))
    }
}
