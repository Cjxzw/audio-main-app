package com.agent.voiceassistant.service

/** Request-scoped retry routing for optional background LLM work. */
internal object BackgroundLlmRetryPlan {
    const val ATTEMPT_COUNT = 3

    fun usesDefaultProvider(capturedProviderIsBuiltIn: Boolean, attempt: Int): Boolean {
        require(attempt in 1..ATTEMPT_COUNT) { "attempt must be between 1 and $ATTEMPT_COUNT" }
        return capturedProviderIsBuiltIn || attempt == ATTEMPT_COUNT
    }
}
