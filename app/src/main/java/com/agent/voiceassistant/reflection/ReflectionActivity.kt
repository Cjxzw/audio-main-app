package com.agent.voiceassistant.reflection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.databinding.ActivityReflectionsBinding
import com.agent.voiceassistant.ui.showLightDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

class ReflectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReflectionsBinding
    private lateinit var store: ReflectionStore
    private val adapter = ReflectionAdapter(::showDetails)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReflectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ReflectionStore(this)
        binding.reflectionsToolbar.setNavigationOnClickListener { finish() }
        binding.reflectionsList.layoutManager = LinearLayoutManager(this)
        binding.reflectionsList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val records = store.records()
        adapter.submit(records)
        binding.reflectionsEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDetails(record: TurnReflectionRecord) {
        val metrics = record.metrics
        val analysis = record.analysis
        val detail = buildString {
            appendLine("状态：${statusLabel(record.status)}")
            appendLine("时间：${DateFormat.getDateTimeInstance().format(Date(record.createdAt))}")
            appendLine("触发：${record.triggerReasons.joinToString("；").ifBlank { "未触发" }}")
            appendLine("总耗时：${duration(metrics.totalDurationMs)}")
            appendLine("首个 SSE 正文：${duration(metrics.timeToFirstContentMs)}")
            appendLine("最终回答首段：${duration(metrics.timeToFinalAnswerContentMs)}")
            appendLine("首次播报：${duration(metrics.timeToFirstAudioMs)}")
            appendLine("工具：${metrics.toolCallCount} 次 / ${metrics.toolRoundCount} 轮 / ${duration(metrics.toolWallDurationMs)}")
            appendLine("累计工具耗时：${duration(metrics.toolAccumulatedDurationMs)}")
            appendLine("最慢工具：${duration(metrics.maxSingleToolDurationMs)}")
            appendLine()
            appendLine("用户任务：")
            appendLine(record.userRequest)
            if (analysis != null) {
                appendLine()
                appendLine(analysis.title)
                appendLine("任务：${analysis.taskSummary}")
                appendLine("性质：${analysis.taskNature.joinToString("、")}")
                appendLine("复杂度：${analysis.complexity}")
                appendLine("委派判断：${analysis.delegationAssessment}")
                appendLine("建议能力：${analysis.preferredCapabilities.joinToString("、").ifBlank { "无" }}")
                appendLine("判断依据：${analysis.whyDelegateOrNot}")
                appendLine("未委派原因：${analysis.whyNotDelegated}")
                appendLine("经验：${analysis.lesson}")
                appendLine("置信度：${"%.2f".format(analysis.confidence)}")
            }
            appendLine()
            append("turnId：${record.turnId}")
        }
        MaterialAlertDialogBuilder(this, R.style.Theme_VoiceAssistant_PreferenceDialog)
            .setTitle(analysis?.title ?: getString(R.string.reflections_detail_title))
            .setMessage(detail)
            .setNegativeButton(R.string.reflections_delete) { _, _ ->
                store.delete(record.turnId)
                reload()
            }
            .setPositiveButton(android.R.string.ok, null)
            .showLightDialog()
    }

    private fun reload() {
        val records = store.records()
        adapter.submit(records)
        binding.reflectionsEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun statusLabel(status: String): String = when (status) {
        "completed" -> "已完成反思"
        "pending" -> "等待反思"
        "failed" -> "反思失败"
        else -> "仅记录指标"
    }

    private fun duration(value: Long?): String = value?.let { "%.2f 秒".format(it / 1000.0) } ?: "无"
}

private class ReflectionAdapter(
    private val onClick: (TurnReflectionRecord) -> Unit,
) : RecyclerView.Adapter<ReflectionAdapter.Holder>() {
    private val items = mutableListOf<TurnReflectionRecord>()

    fun submit(records: List<TurnReflectionRecord>) {
        items.clear()
        items.addAll(records)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_reflection, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.tvReflectionTitle)
        private val metrics = view.findViewById<TextView>(R.id.tvReflectionMetrics)
        private val summary = view.findViewById<TextView>(R.id.tvReflectionSummary)

        fun bind(record: TurnReflectionRecord) {
            title.text = record.analysis?.title ?: when (record.status) {
                "pending" -> "[反思] 等待分析"
                "failed" -> "[反思] 分析失败"
                else -> "回合指标"
            }
            metrics.text = "${duration(record.metrics.totalDurationMs)} · " +
                "${record.metrics.toolCallCount} 次工具 · ${record.metrics.toolRoundCount} 轮"
            summary.text = record.analysis?.taskSummary ?: record.userRequest
            itemView.setOnClickListener { onClick(record) }
        }

        private fun duration(value: Long) = "%.1f 秒".format(value / 1000.0)
    }
}
