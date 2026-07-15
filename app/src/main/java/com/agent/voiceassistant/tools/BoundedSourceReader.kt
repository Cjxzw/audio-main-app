package com.agent.voiceassistant.tools

import okio.Buffer
import okio.BufferedSource

internal object BoundedSourceReader {
    data class Result(val bytes: ByteArray, val truncated: Boolean)

    fun read(source: BufferedSource, maxBytes: Long): Result {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val buffer = Buffer()
        var remaining = maxBytes + 1
        while (remaining > 0) {
            val count = source.read(buffer, minOf(8_192L, remaining))
            if (count == -1L) break
            remaining -= count
        }
        val truncated = buffer.size > maxBytes
        val keptBytes = minOf(buffer.size, maxBytes)
        return Result(buffer.readByteArray(keptBytes), truncated)
    }
}
