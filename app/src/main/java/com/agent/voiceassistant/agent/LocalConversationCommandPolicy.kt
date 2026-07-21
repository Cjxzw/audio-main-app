package com.agent.voiceassistant.agent

object LocalConversationCommandPolicy {
    enum class Command {
        NEW_TOPIC,
        SLEEP,
    }

    fun classify(text: String): Command? {
        val normalized = normalize(text)
        return when {
            normalized == "/new" || normalized in NEW_TOPIC_PHRASES -> Command.NEW_TOPIC
            normalized in SLEEP_PHRASES -> Command.SLEEP
            else -> null
        }
    }

    private fun normalize(text: String): String = text
        .trim()
        .lowercase()
        .replace(IGNORED_SEPARATORS, "")

    private val NEW_TOPIC_PHRASES = setOf(
        "开启新话题",
        "新建会话",
        "新开话题",
        "重新开始一个话题",
        "重新开始",
    )

    private val SLEEP_PHRASES = setOf(
        "退下",
        "退下吧",
        "再见",
        "拜拜",
        "你走吧",
        "你走开",
        "没事了",
        "没事儿了",
        "没事啦",
        "没事儿啦",
        "没你的事了",
        "没你事了",
        "滚吧",
        "滚蛋",
        "休眠",
        "进入休眠",
        "先休眠",
        "可以休眠了",
        "休息吧",
        "睡吧",
    )

    private val IGNORED_SEPARATORS = Regex("[\\s，。！？!?、,.~～…]+")
}
