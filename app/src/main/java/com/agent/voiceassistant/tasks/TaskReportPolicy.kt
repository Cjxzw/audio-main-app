package com.agent.voiceassistant.tasks

object TaskReportPolicy {
    enum class Route { ACTIVE_AUDIO, DORMANT_EXTERNAL_AUDIO, NOTIFICATION, DEFER }

    fun route(
        dormant: Boolean,
        sameConversation: Boolean,
        externalOutputConnected: Boolean,
        userSpeaking: Boolean,
        audioReportsEnabled: Boolean = true,
    ): Route = when {
        !audioReportsEnabled -> Route.NOTIFICATION
        !sameConversation -> Route.NOTIFICATION
        !externalOutputConnected -> Route.NOTIFICATION
        dormant && externalOutputConnected -> Route.DORMANT_EXTERNAL_AUDIO
        userSpeaking -> Route.DEFER
        else -> Route.ACTIVE_AUDIO
    }
}
