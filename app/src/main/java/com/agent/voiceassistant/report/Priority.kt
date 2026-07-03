package com.agent.voiceassistant.report

/**
 * 任务优先级。weight 越小优先级越高（PriorityQueue 用）。
 */
enum class Priority(val weight: Int) {
    /** 紧急：可打断 Bot 当前播报 */
    URGENT(0),

    /** 普通：闲时汇报，忙时排队 */
    NORMAL(10),

    /** 低优先级：下次空闲时再汇报 */
    LOW(20)
}
