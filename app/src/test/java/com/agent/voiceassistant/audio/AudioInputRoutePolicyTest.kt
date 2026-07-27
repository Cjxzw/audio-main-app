package com.agent.voiceassistant.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputRoutePolicyTest {
    @Test
    fun `built in microphone wins over telephony pseudo device`() {
        assertTrue(
            AudioInputRoutePolicy.priority(AudioDeviceInfo.TYPE_BUILTIN_MIC, Int.MIN_VALUE) <
                AudioInputRoutePolicy.priority(AudioDeviceInfo.TYPE_TELEPHONY, Int.MIN_VALUE),
        )
    }

    @Test
    fun `external microphone wins over built in microphone`() {
        assertTrue(
            AudioInputRoutePolicy.priority(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, Int.MIN_VALUE) <
                AudioInputRoutePolicy.priority(AudioDeviceInfo.TYPE_BUILTIN_MIC, Int.MIN_VALUE),
        )
    }
}
