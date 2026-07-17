package com.agent.voiceassistant.cloud

class StreamingSpeechExtractor {
    private enum class State { TEXT, REPLY, TAG, TOOL, JSON_ONLY }

    private var state = State.TEXT
    private var previousState = State.TEXT
    private val tagBuffer = StringBuilder()
    private val toolTail = StringBuilder()
    private var sawFirstVisible = false

    fun feed(delta: String): String {
        if (delta.isEmpty()) return ""
        val out = StringBuilder()
        for (ch in delta) {
            when (state) {
                State.JSON_ONLY -> Unit
                State.TOOL -> feedTool(ch)
                State.TAG -> feedTag(ch, out)
                State.TEXT, State.REPLY -> feedText(ch, out)
            }
        }
        return out.toString()
    }

    fun finish(): String {
        if (state == State.TAG && tagBuffer.isNotEmpty()) {
            val text = tagBuffer.toString()
            tagBuffer.clear()
            state = previousState
            return text
        }
        return ""
    }

    private fun feedText(ch: Char, out: StringBuilder) {
        if (!sawFirstVisible && !ch.isWhitespace()) {
            sawFirstVisible = true
            if (ch == '{') {
                state = State.JSON_ONLY
                return
            }
        }
        if (ch == '<') {
            previousState = state
            state = State.TAG
            tagBuffer.clear()
            tagBuffer.append(ch)
            return
        }
        out.append(ch)
    }

    private fun feedTag(ch: Char, out: StringBuilder) {
        tagBuffer.append(ch)
        if (tagBuffer.length > MAX_TAG_CHARS) {
            out.append(tagBuffer)
            tagBuffer.clear()
            state = previousState
            return
        }
        if (ch != '>') return

        val tag = tagBuffer.toString().trim().lowercase()
        tagBuffer.clear()
        when (tag) {
            "<reply>" -> state = State.REPLY
            "</reply>" -> state = State.TEXT
            "<local_action>", "<hub_action>", "<tool_call>" -> {
                toolTail.clear()
                state = State.TOOL
            }
            "</local_action>", "</hub_action>", "</tool_call>" -> state = State.TEXT
            else -> {
                out.append("<").append(tag.removePrefix("<").removeSuffix(">")).append(">")
                state = previousState
            }
        }
    }

    private fun feedTool(ch: Char) {
        toolTail.append(ch)
        if (toolTail.length > TOOL_TAIL_CHARS) {
            toolTail.delete(0, toolTail.length - TOOL_TAIL_CHARS)
        }
        val tail = toolTail.toString().lowercase()
        if (tail.endsWith("</local_action>") ||
            tail.endsWith("</hub_action>") ||
            tail.endsWith("</tool_call>")
        ) {
            toolTail.clear()
            state = State.TEXT
        }
    }

    private companion object {
        private const val MAX_TAG_CHARS = 48
        private const val TOOL_TAIL_CHARS = 64
    }
}
