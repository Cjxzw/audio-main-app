package com.agent.voiceassistant.tasks

object TaskReportPolicy {
    enum class Route { ACTIVE_AUDIO, DORMANT_EXTERNAL_AUDIO, NOTIFICATION, DEFER }

    fun route(
        dormant: Boolean,
        sameConversation: Boolean,
        externalOutputConnected: Boolean,
        userSpeaking: Boolean,
    ): Route = when {
        !sameConversation -> Route.NOTIFICATION
        dormant && externalOutputConnected -> Route.DORMANT_EXTERNAL_AUDIO
        dormant -> Route.NOTIFICATION
        userSpeaking -> Route.DEFER
        else -> Route.ACTIVE_AUDIO
    }
}
