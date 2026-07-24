package com.agent.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.tasks.TaskEntity
import com.agent.voiceassistant.tasks.TaskPriority
import com.agent.voiceassistant.tasks.TaskReportState
import com.agent.voiceassistant.tasks.TaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskAdapter(
    private val onOpen: (TaskEntity) -> Unit,
    private val onCancel: (TaskEntity) -> Unit,
) : ListAdapter<TaskEntity, TaskAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), onOpen, onCancel)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.tvTaskTitle)
        private val state = view.findViewById<TextView>(R.id.tvTaskState)
        private val progress = view.findViewById<ProgressBar>(R.id.taskProgress)
        private val cancel = view.findViewById<ImageButton>(R.id.btnTaskCancel)
        private val date = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

        fun bind(item: TaskEntity, onOpen: (TaskEntity) -> Unit, onCancel: (TaskEntity) -> Unit) {
            val urgent = if (item.priority == TaskPriority.URGENT.name) "紧急 · " else ""
            val report = if (item.reportState == TaskReportState.REPORTED.name) " · 已汇报" else ""
            title.text = item.title
            state.text = "$urgent${statusLabel(item.status)} · ${item.executorName} · ${date.format(Date(item.updatedAt))}$report"
            progress.progress = item.progress
            progress.visibility = if (item.status == TaskStatus.RUNNING.name || item.status == TaskStatus.QUEUED.name) View.VISIBLE else View.INVISIBLE
            cancel.visibility = if (item.status == TaskStatus.RUNNING.name || item.status == TaskStatus.QUEUED.name) View.VISIBLE else View.INVISIBLE
            itemView.setOnClickListener { onOpen(item) }
            cancel.setOnClickListener { onCancel(item) }
        }

        private fun statusLabel(status: String) = when (status) {
            TaskStatus.QUEUED.name -> "等待中"
            TaskStatus.RUNNING.name -> "工作中"
            TaskStatus.COMPLETED.name -> "成功完成"
            TaskStatus.FAILED.name -> "完成失败"
            TaskStatus.INTERRUPTED.name -> "已中断"
            TaskStatus.CANCELLED.name -> "已取消"
            TaskStatus.BLOCKED.name -> "已阻塞"
            else -> status
        }
    }

    private object Diff : DiffUtil.ItemCallback<TaskEntity>() {
        override fun areItemsTheSame(oldItem: TaskEntity, newItem: TaskEntity) = oldItem.taskId == newItem.taskId
        override fun areContentsTheSame(oldItem: TaskEntity, newItem: TaskEntity) = oldItem == newItem
    }
}
