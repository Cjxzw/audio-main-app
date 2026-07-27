package com.agent.voiceassistant.cloud

import com.agent.voiceassistant.agent.SpokenReplyPolicy

class StreamingSpeechExtractor {
    private enum class State { TEXT, REPLY, TAG, TOOL, CODE_FENCE, DISPLAY_DETAIL, MARKDOWN_TABLE }

    private var state = State.TEXT
    private var previousState = State.TEXT
    private val tagBuffer = StringBuilder()
    private val toolTail = StringBuilder()
    private var toolClosingTag = ""
    private val backtickBuffer = StringBuilder()
    private var codeReturnState = State.TEXT
    private var closingBackticks = 0
    private var sawFirstVisible = false
    private var shouldAnnounceDetails = false
    private var emittedVisibleText = false
    private var atLineStart = true
    private var tablePreviousWasNewline = false

    fun feed(delta: String): String {
        if (delta.isEmpty()) return ""
        val out = StringBuilder()
        for (ch in delta) {
            when (state) {
                State.DISPLAY_DETAIL -> Unit
                State.MARKDOWN_TABLE -> feedMarkdownTable(ch)
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
        if (shouldAnnounceDetails) {
            if (emittedVisibleText) output.append(' ')
            output.append(SpokenReplyPolicy.DETAILS_NOTICE)
        }
        return output.toString()
    }

    private fun feedText(ch: Char, out: StringBuilder) {
        if (atLineStart && ch == '|') {
            state = State.MARKDOWN_TABLE
            tablePreviousWasNewline = false
            return
        }
        if (ch == '\n') {
            atLineStart = true
            appendVisible(out, ch.toString())
            return
        }
        if (!ch.isWhitespace()) atLineStart = false
        if (ch == '*' || ch == '_') return
        if (ch == '`') {
            backtickBuffer.append(ch)
            if (backtickBuffer.length == 3) {
                codeReturnState = state
                state = State.CODE_FENCE
                backtickBuffer.clear()
                closingBackticks = 0
                shouldAnnounceDetails = true
            }
            return
        }
        if (backtickBuffer.isNotEmpty()) {
            appendVisible(out, backtickBuffer.toString())
            backtickBuffer.clear()
        }
        if (!sawFirstVisible && !ch.isWhitespace()) {
            sawFirstVisible = true
            if (ch == '{' || ch == '[') {
                state = State.DISPLAY_DETAIL
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
            "<detail>" -> enterDetail("</detail>")
            "<details>" -> enterDetail("</details>")
            else -> when {
                tag == "<function>" || tag.startsWith("<function=") -> enterTool("</function>")
                tag == "<parameter>" || tag.startsWith("<parameter=") -> enterTool("</parameter>")
                !emittedVisibleText && previousState == State.TEXT -> state = State.DISPLAY_DETAIL
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

    private fun enterDetail(closingTag: String) {
        shouldAnnounceDetails = true
        enterTool(closingTag)
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

    private fun feedMarkdownTable(ch: Char) {
        if (ch == '\n') {
            if (tablePreviousWasNewline) {
                state = State.TEXT
                atLineStart = true
                tablePreviousWasNewline = false
            } else {
                tablePreviousWasNewline = true
            }
        } else if (!ch.isWhitespace()) {
            tablePreviousWasNewline = false
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
            atLineStart = true
        }
    }

    private companion object {
        private const val MAX_TAG_CHARS = 48
        private const val TOOL_TAIL_CHARS = 64
    }
}
