package com.agent.voiceassistant.settings

import android.content.Context

class SpeechPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var ttsGainPercent: Int
        get() = preferences.getInt(KEY_TTS_GAIN, DEFAULT_TTS_GAIN).coerceIn(MIN_TTS_GAIN, MAX_TTS_GAIN)
        set(value) {
            preferences.edit().putInt(KEY_TTS_GAIN, normalize(value)).apply()
        }

    val ttsGain: Float
        get() = ttsGainPercent / 100f

    var muteTextReplies: Boolean
        get() = preferences.getBoolean(KEY_MUTE_TEXT_REPLIES, false)
        set(value) {
            preferences.edit().putBoolean(KEY_MUTE_TEXT_REPLIES, value).apply()
        }

    private fun normalize(value: Int): Int {
        val bounded = value.coerceIn(MIN_TTS_GAIN, MAX_TTS_GAIN)
        return ((bounded - MIN_TTS_GAIN) / STEP * STEP) + MIN_TTS_GAIN
    }

    companion object {
        const val MIN_TTS_GAIN = 50
        const val MAX_TTS_GAIN = 150
        const val DEFAULT_TTS_GAIN = 120
        const val STEP = 5
        private const val PREFERENCES = "speech_settings"
        private const val KEY_TTS_GAIN = "tts_gain_percent"
        private const val KEY_MUTE_TEXT_REPLIES = "mute_text_replies"
    }
}
