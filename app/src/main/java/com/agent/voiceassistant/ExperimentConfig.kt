package com.agent.voiceassistant

/** Temporary switches for the pseudo-reasoning text experiment. */
object ExperimentConfig {
    const val ENABLE_INTENT_ROUTING = false
    const val ENABLE_FINAL_REFINEMENT = false
    const val ENABLE_TTS = true
    const val ENABLE_PERSONALIZED_TTS_TOOL = false
    const val SHOW_RAW_MODEL_TEXT = true
    const val ENABLE_STRUCTURED_REPLY_PRESENTATION = true
    const val ENABLE_FINAL_RESPONSE_FORMAT_REPAIR = true
    const val ENABLE_BODY_TOOL_ADAPTER = true
    const val ENABLE_MODEL_FORMAT_REPAIR = false
    const val ENABLE_EMPTY_FINAL_RETRY = false
    const val ENABLE_STREAM_INTEGRITY_RETRY = false
    const val ENABLE_ACTIVE_TOOL_BUDGET = false
    const val ENABLE_FORCED_FINAL_SUMMARY = false
    const val INCLUDE_BUILTIN_DIAGNOSTIC_RULE = false
}
