package com.agent.voiceassistant.report

/**
 * 任务列表摘要（常驻内存，用于实时决策）。
 *
 * 工程约定（来自项目记忆）：
 * - 显示所有 in_progress 任务 + 所有 unreported 任务 + 最新 5 条 reported 任务
 * - Coordinator 架构：常驻加载任务列表摘要 + 按需加载 agent 列表
 *
 * 此类常驻内存，不依赖数据库查询。增删改用 synchronized 保护原子性。
 */
class TaskListSummary(
    /** reported 任务保留的最大数量 */
    private val maxReportedKeep: Int = 5
) {
    private val tasks = mutableListOf<DispatchedTask>()
    private val lock = Any()

    /** 添加新任务 */
    fun add(task: DispatchedTask) = synchronized(lock) {
        tasks.add(task)
        prune()
    }

    /** 按 ID 查询 */
    fun get(id: String): DispatchedTask? = synchronized(lock) {
        tasks.find { it.id == id }
    }

    /** 更新任务（报告内容 + 状态更新原子性） */
    fun update(taskId: String, block: (DispatchedTask) -> Unit): DispatchedTask? =
        synchronized(lock) {
            val task = tasks.find { it.id == taskId } ?: return@synchronized null
            block(task)
            prune()
            task
        }

    /** 所有进行中任务 */
    fun inProgressTasks(): List<DispatchedTask> = synchronized(lock) {
        tasks.filter { it.status == TaskStatus.in_progress }
    }

    /** 所有未汇报任务 */
    fun unreportedTasks(): List<DispatchedTask> = synchronized(lock) {
        tasks.filter { it.needsReport }
    }

    /** 最近 N 条已汇报任务 */
    fun recentReported(): List<DispatchedTask> = synchronized(lock) {
        tasks.filter { it.reported }
            .sortedByDescending { it.reportedAt ?: 0 }
            .take(maxReportedKeep)
    }

    /** 摘要视图（适合 LLM 上下文） */
    fun summaryText(): String = synchronized(lock) {
        buildString {
            append("进行中: ${inProgressTasks().size} 项\n")
            append("待汇报: ${unreportedTasks().size} 项\n")
            append("已汇报: ${recentReported().size} 项\n")
            inProgressTasks().take(3).forEach {
                append("- [${it.taskType}] ${it.description.take(40)}\n")
            }
            if (unreportedTasks().isNotEmpty()) {
                append("待汇报任务:\n")
                unreportedTasks().take(3).forEach {
                    append("- ${it.id}: ${it.summary?.take(40) ?: "无摘要"}\n")
                }
            }
        }
    }

    /** 裁剪：保留 in_progress + unreported + 最近 maxReportedKeep 条 reported */
    private fun prune() {
        val toKeep = mutableListOf<DispatchedTask>()
        toKeep.addAll(tasks.filter { it.status == TaskStatus.in_progress })
        toKeep.addAll(tasks.filter { it.needsReport })
        // 已汇报的按时间排序保留最新 N 条
        val reportedSorted = tasks
            .filter { it.reported }
            .sortedByDescending { it.reportedAt ?: 0 }
            .take(maxReportedKeep)
        toKeep.addAll(reportedSorted)

        // 去重并替换原列表
        val seen = HashSet<String>()
        val unique = toKeep.filter { seen.add(it.id) }
        tasks.clear()
        tasks.addAll(unique)
    }

    fun size(): Int = synchronized(lock) { tasks.size }
}
