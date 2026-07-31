package com.agent.voiceassistant.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.databinding.ItemHubAgentBinding
import com.agent.voiceassistant.hub.HubAgentFact

class HubAgentAdapter : ListAdapter<HubAgentFact, HubAgentAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemHubAgentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(private val binding: ItemHubAgentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(agent: HubAgentFact) {
            binding.tvAgentName.text = agent.name.ifBlank { agent.agentId }
            binding.tvAgentId.text = agent.agentId
            binding.tvAgentStatus.text = if (agent.online || agent.status.equals("online", true)) "在线" else "离线"
            binding.tvAgentStatus.setTextColor(Color.parseColor(if (agent.online || agent.status.equals("online", true)) "#16803C" else "#8A8A8A"))
            binding.tvAgentMeta.text = buildString {
                append(if (agent.companion) "项目伴生 Agent" else "独立执行器")
                listOf(agent.model, agent.version).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                    ?.let { append(" · ").append(it.joinToString(" ")) }
                agent.subId?.takeIf { it.isNotBlank() }?.let { append("\n宿主：").append(it) }
                agent.projectName.takeIf { it.isNotBlank() }?.let { append(" · 项目：").append(it) }
                if (agent.capabilities.isNotEmpty()) {
                    append("\n能力：").append(agent.capabilities.joinToString(" · ") { capabilityLabel(it) })
                }
                agent.description.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            }
        }

        private fun capabilityLabel(capability: String): String = when (capability.lowercase()) {
            "task" -> "任务执行"
            "interactive" -> "交互协作"
            "executor" -> "执行器"
            "code", "coding" -> "代码"
            "research" -> "调研"
            "shell" -> "Shell"
            "file", "files" -> "文件"
            "report" -> "报告"
            else -> capability
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<HubAgentFact>() {
            override fun areItemsTheSame(oldItem: HubAgentFact, newItem: HubAgentFact) = oldItem.agentId == newItem.agentId
            override fun areContentsTheSame(oldItem: HubAgentFact, newItem: HubAgentFact) = oldItem == newItem
        }
    }
}
