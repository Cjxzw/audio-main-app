package com.agent.voiceassistant.cloud

import com.agent.voiceassistant.agent.SpokenReplyPolicy

class StreamingSpeechExtractor {
    private enum class State { TEXT, REPLY, TAG, TOOL, CODE_FENCE, JSON_ONLY }

    private var state = State.TEXT
    private var previousState = State.TEXT
    private val tagBuffer = StringBuilder()
    private val toolTail = StringBuilder()
    private var toolClosingTag = ""
    private val backtickBuffer = StringBuilder()
    private var codeReturnState = State.TEXT
    private var closingBackticks = 0
    private var sawFirstVisible = false
    private var sawFencedDetails = false
    private var emittedVisibleText = false

    fun feed(delta: String): String {
        if (delta.isEmpty()) return ""
        val out = StringBuilder()
        for (ch in delta) {
            when (state) {
                State.JSON_ONLY -> Unit
                State.TOOL -> feedTool(ch)
                State.CODE_FENCE -> feedCodeFence(ch)
                State.TAG -> feedTag(ch, out)
                State.TEXT, State.REPLY -> feedText(ch, out)
            }
        }
        return out.toString()
    }

    fun finish(): String {
        val output = StringBuilder()
        if (state != State.CODE_FENCE && backtickBuffer.isNotEmpty()) {
            appendVisible(output, backtickBuffer.toString())
            backtickBuffer.clear()
        }
        if (state == State.TAG && tagBuffer.isNotEmpty()) {
            appendVisible(output, tagBuffer.toString())
            tagBuffer.clear()
            state = previousState
        }
        if (sawFencedDetails && emittedVisibleText) {
            output.append(' ').append(SpokenReplyPolicy.DETAILS_NOTICE)
        }
        return output.toString()
    }

    private fun feedText(ch: Char, out: StringBuilder) {
        if (ch == '`') {
            backtickBuffer.append(ch)
            if (backtickBuffer.length == 3) {
                codeReturnState = state
                state = State.CODE_FENCE
                backtickBuffer.clear()
                closingBackticks = 0
                sawFencedDetails = true
            }
            return
        }
        if (backtickBuffer.isNotEmpty()) {
            appendVisible(out, backtickBuffer.toString())
            backtickBuffer.clear()
        }
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
        appendVisible(out, ch.toString())
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
            "<local_action>" -> enterTool("</local_action>")
            "<hub_action>" -> enterTool("</hub_action>")
            "<tool_call>" -> enterTool("</tool_call>")
            else -> when {
                tag == "<function>" || tag.startsWith("<function=") -> enterTool("</function>")
                tag == "<parameter>" || tag.startsWith("<parameter=") -> enterTool("</parameter>")
                else -> {
                    appendVisible(out, "<${tag.removePrefix("<").removeSuffix(">")}>")
                    state = previousState
                }
            }
        }
    }

    private fun enterTool(closingTag: String) {
        toolTail.clear()
        toolClosingTag = closingTag
        state = State.TOOL
    }

    private fun feedCodeFence(ch: Char) {
        if (ch == '`') {
            closingBackticks++
            if (closingBackticks == 3) {
                closingBackticks = 0
                state = codeReturnState
            }
        } else {
            closingBackticks = 0
        }
    }

    private fun appendVisible(out: StringBuilder, text: String) {
        out.append(text)
        if (text.any { !it.isWhitespace() }) emittedVisibleText = true
    }

    private fun feedTool(ch: Char) {
        toolTail.append(ch)
        if (toolTail.length > TOOL_TAIL_CHARS) {
            toolTail.delete(0, toolTail.length - TOOL_TAIL_CHARS)
        }
        val tail = toolTail.toString().lowercase()
        if (toolClosingTag.isNotEmpty() && tail.endsWith(toolClosingTag)) {
            toolTail.clear()
            toolClosingTag = ""
            state = State.TEXT
        }
    }

    private companion object {
        private const val MAX_TAG_CHARS = 48
        private const val TOOL_TAIL_CHARS = 64
    }
}
