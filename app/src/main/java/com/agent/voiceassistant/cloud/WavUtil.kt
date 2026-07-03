package com.agent.voiceassistant.cloud

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtil {
    fun pcm16ToWav(samples: ShortArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val dataSize = samples.size * 2
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putAscii("RIFF")
        buffer.putInt(36 + dataSize)
        buffer.putAscii("WAVE")
        buffer.putAscii("fmt ")
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(16)
        buffer.putAscii("data")
        buffer.putInt(dataSize)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    private fun ByteBuffer.putAscii(value: String) {
        put(value.toByteArray(Charsets.US_ASCII))
    }
}
