package com.agent.voiceassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPatchApplierTest {
    @Test
    fun replacesOnlyRequestedLines() {
        val updated = TextPatchApplier.apply(
            current = "one\ntwo\nthree\nfour",
            replacement = "TWO\nTHREE",
            startLine = 2,
            endLine = 3,
        )

        assertEquals("one\nTWO\nTHREE\nfour", updated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingLineRange() {
        TextPatchApplier.apply("one", "new", null, null)
    }
}
