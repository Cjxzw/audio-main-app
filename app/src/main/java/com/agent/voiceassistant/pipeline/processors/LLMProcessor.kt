package com.agent.voiceassistant.pipeline.processors

import com.agent.voiceassistant.agent.Assistant
import com.agent.voiceassistant.pipeline.FrameProcessor
import com.agent.voiceassistant.pipeline.aggregators.SentenceAggregator
import com.agent.voiceassistant.pipeline.frames.DataFrame
import com.agent.voiceassistant.pipeline.frames.Frame
import com.agent.voiceassistant.pipeline.frames.FrameDirection
import com.agent.voiceassistant.pipeline.frames.SystemFrame
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.ui.ChatMessage
import com.agent.voiceassistant.ui.ChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class LLMProcessor(
    private val assistant: Assistant
) : FrameProcessor() {

    private val sentenceAggregator = SentenceAggregator(maxChars = 80)
    private val responding = AtomicBoolean(false)
    private val interrupted = AtomicBoolean(false)

    override suspend fun processFrame(frame: Frame, direction: FrameDirection) {
        when (frame) {
            is SystemFrame.StartFrame -> {
                Timber.i("LLMProcessor: start")
                interrupted.set(false)
            }
            is SystemFrame.EndFrame, is SystemFrame.CancelFrame -> {
                interrupted.set(true)
                sentenceAggregator.reset()
            }
            is SystemFrame.InterruptionFrame -> {
                interrupted.set(true)
                Timber.i("LLM: interrupted, will skip pending TTS push")
                sentenceAggregator.reset()
            }
            is DataFrame.TranscriptionFrame -> handleTranscription(frame)
            is DataFrame.TextFrame -> handleInjection(frame)
            is DataFrame.LLMTextFrame -> {
                feedAndPush(frame.text)
            }
            is DataFrame.LLMResponseEndFrame -> {
                flushRemaining()
                responding.set(false)
                pushFrame(frame, direction)
            }
            is SystemFrame -> Unit
            else -> pushFrame(frame, direction)
        }
    }

    private suspend fun handleTranscription(frame: DataFrame.TranscriptionFrame) {
        if (!frame.isFinal) return

        EventBus.emitChatMessage(ChatMessage(ChatRole.USER, frame.text))
        Timber.i("LLM: chat(user='${frame.text}')")

        interrupted.set(false)
        responding.set(true)

        val response = try {
            withContext(Dispatchers.IO) {
                assistant.chat(frame.text)
            }
        } catch (e: Exception) {
            Timber.e(e, "LLM chat failed")
            responding.set(false)
            val errorMsg = "抱歉，我处理失败了，请重试。"
            EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, errorMsg))
            pushFrame(
                DataFrame.TTSTextFrame(errorMsg),
                FrameDirection.DOWNSTREAM
            )
            return
        }

        if (interrupted.get()) {
            Timber.i("LLM: response arrived but interrupted")
            responding.set(false)
            return
        }

        Timber.i("LLM: response='$response'")
        EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, response))
        feedAndPush(response)
        flushRemaining()
        responding.set(false)

        pushFrame(
            DataFrame.LLMResponseEndFrame(fullText = response),
            FrameDirection.DOWNSTREAM
        )
    }

    private suspend fun handleInjection(frame: DataFrame.TextFrame) {
        Timber.i("LLM: inject(text='${frame.text.take(50)}...')")
        interrupted.set(false)
        responding.set(true)
        val response = try {
            withContext(Dispatchers.IO) {
                assistant.inject(frame.text)
            }
        } catch (e: Exception) {
            Timber.e(e, "LLM inject failed")
            responding.set(false)
            return
        }
        if (interrupted.get()) {
            responding.set(false)
            return
        }
        Timber.i("LLM: inject response='$response'")
        EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, response))
        feedAndPush(response)
        flushRemaining()
        responding.set(false)
        pushFrame(
            DataFrame.LLMResponseEndFrame(fullText = response),
            FrameDirection.DOWNSTREAM
        )
    }

    private suspend fun feedAndPush(text: String) {
        val sentences = sentenceAggregator.feed(text)
        for (s in sentences) {
            if (interrupted.get()) return
            Timber.d("LLM: sentence -> '$s'")
            pushFrame(DataFrame.TTSTextFrame(s), FrameDirection.DOWNSTREAM)
        }
    }

    private suspend fun flushRemaining() {
        sentenceAggregator.flush()?.let { remaining ->
            if (!interrupted.get()) {
                Timber.d("LLM: flush -> '$remaining'")
                pushFrame(DataFrame.TTSTextFrame(remaining), FrameDirection.DOWNSTREAM)
            }
        }
    }

    override suspend fun cleanup() {
        super.cleanup()
        interrupted.set(true)
        responding.set(false)
        sentenceAggregator.reset()
        interrupted.set(false)
    }
}
