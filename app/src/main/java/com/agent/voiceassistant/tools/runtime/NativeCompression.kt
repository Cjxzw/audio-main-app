package com.agent.voiceassistant.tools.runtime

internal object NativeCompression {
    init { System.loadLibrary("hanwo_quickjs") }
    external fun transform(inputFd: Int, outputFd: Int, operation: String, maxBytes: Long, deadlineNanos: Long): Long
}
