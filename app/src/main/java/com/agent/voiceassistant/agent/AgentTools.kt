package com.agent.voiceassistant.agent

import com.agent.voiceassistant.report.PendingResult
import com.agent.voiceassistant.report.PendingResultReporter
import com.agent.voiceassistant.report.Priority
import com.agent.voiceassistant.service.TaskDispatcher
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AgentTools：LangChain4j @Tool 工具集合。
 *
 * 工具由 LLM 自动调用，返回 String 注入回 LLM 上下文。
 * 复杂工作通过 [TaskDispatcher] 委派给 PC 端 Agent。
 *
 * 注意：工具对象需在 [AgentFactory] 中以 `.tools(tools)` 注册。
 */
class AgentTools(
    private val taskDispatcher: TaskDispatcher
) {

    @Tool("查询指定城市的天气，返回简短的天气信息")
    fun getWeather(@P("城市名称") city: String): String {
        Timber.i("Tool: getWeather(city=$city)")
        // MVP 阶段返回固定示例，后续接入真实天气 API
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        return "今天 $today $city 多云，气温 22-30°C，建议出行带伞"
    }

    @Tool("查询当前时间")
    fun getCurrentTime(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        Timber.i("Tool: getCurrentTime() -> $now")
        return "当前时间是 $now"
    }

    @Tool("查询简单计算结果，参数是表达式字符串")
    fun calculate(@P("数学表达式，如 1+2*3") expression: String): String {
        Timber.i("Tool: calculate($expression)")
        return try {
            // 简单安全计算：仅支持数字和运算符
            val sanitized = expression.replace("[^0-9+\\-*/().\\s]".toRegex(), "")
            if (sanitized.isBlank()) return "无法计算"
            val result = evalSimple(sanitized)
            "$expression = $result"
        } catch (e: Exception) {
            Timber.e(e, "calculate failed")
            "计算失败：${e.message}"
        }
    }

    @Tool("委派后台任务到 PC 端 Agent 执行，返回任务 ID。任务类型：research/analysis/coding/summary")
    fun dispatchTask(
        @P("任务类型：research/analysis/coding/summary") taskType: String,
        @P("任务描述") description: String,
        @P("优先级：urgent/normal/low，默认 normal") priority: String = "normal"
    ): String {
        Timber.i("Tool: dispatchTask(type=$taskType, desc=$description, priority=$priority)")
        val p = when (priority.lowercase()) {
            "urgent" -> Priority.URGENT
            "low" -> Priority.LOW
            else -> Priority.NORMAL
        }
        val taskId = taskDispatcher.submit(taskType, description, p)
        return """{"task_id":"$taskId","status":"dispatched","type":"$taskType"}"""
    }

    @Tool("查询已派出的任务状态")
    fun queryTaskStatus(@P("任务 ID") taskId: String): String {
        Timber.i("Tool: queryTaskStatus($taskId)")
        val task = taskDispatcher.getTask(taskId)
            ?: return "任务 $taskId 不存在"
        return """{"task_id":"${task.id}","status":"${task.status}","summary":"${task.summary ?: "进行中"}"}"""
    }

    /**
     * 简易算术表达式求值（不依赖 ScriptEngine，避免 Android 兼容性问题）。
     * 仅支持 + - * / () 和数字。
     */
    private fun evalSimple(expr: String): Double {
        return object : Any() {
            fun parse(): Double {
                val pos = intArrayOf(0)
                return parseExpr(expr.replace(" ", "").toCharArray(), pos)
            }

            fun parseExpr(s: CharArray, pos: IntArray): Double {
                var v = parseTerm(s, pos)
                while (pos[0] < s.size && (s[pos[0]] == '+' || s[pos[0]] == '-')) {
                    val op = s[pos[0]++]
                    val r = parseTerm(s, pos)
                    v = if (op == '+') v + r else v - r
                }
                return v
            }

            fun parseTerm(s: CharArray, pos: IntArray): Double {
                var v = parseFactor(s, pos)
                while (pos[0] < s.size && (s[pos[0]] == '*' || s[pos[0]] == '/')) {
                    val op = s[pos[0]++]
                    val r = parseFactor(s, pos)
                    v = if (op == '*') v * r else v / r
                }
                return v
            }

            fun parseFactor(s: CharArray, pos: IntArray): Double {
                if (pos[0] < s.size && s[pos[0]] == '(') {
                    pos[0]++
                    val v = parseExpr(s, pos)
                    if (pos[0] < s.size && s[pos[0]] == ')') pos[0]++
                    return v
                }
                val start = pos[0]
                while (pos[0] < s.size && (s[pos[0]].isDigit() || s[pos[0]] == '.')) pos[0]++
                if (start == pos[0]) throw IllegalArgumentException("invalid number at $start")
                return s.concatToString().substring(start, pos[0]).toDouble()
            }
        }.parse()
    }
}
