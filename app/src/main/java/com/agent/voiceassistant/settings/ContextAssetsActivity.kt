package com.agent.voiceassistant.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.data.RuleStore
import com.agent.voiceassistant.databinding.ActivityContextAssetsBinding
import com.agent.voiceassistant.editor.TextEditorActivity
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import com.agent.voiceassistant.ui.showLightDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class ContextAssetsActivity : AppCompatActivity() {
    private enum class Tab { SKILLS, MEMORIES, RULES }

    private lateinit var binding: ActivityContextAssetsBinding
    private lateinit var store: ConversationStore
    private lateinit var ruleStore: RuleStore
    private lateinit var skills: SkillRegistry
    private lateinit var adapter: ContextAssetAdapter
    private var tab = Tab.SKILLS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContextAssetsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ConversationStore(this)
        ruleStore = RuleStore(this)
        val env = AndroidExecutionEnv(this)
        skills = SkillRegistry(env.skillsRoot, env.disabledSkillsRoot, env.deletedSkillsManifest, env.modifiedSkillsManifest)
        adapter = ContextAssetAdapter(::toggle, ::edit, ::confirmDelete)
        binding.contextAssetsList.layoutManager = LinearLayoutManager(this)
        binding.contextAssetsList.adapter = adapter
        binding.contextAssetsToolbar.inflateMenu(R.menu.menu_context_assets)
        binding.contextAssetsToolbar.setNavigationOnClickListener { finish() }
        binding.contextAssetsToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_add_context_asset) {
                addCurrentAsset()
                true
            } else {
                false
            }
        }
        binding.contextAssetTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            tab = when (checkedId) {
                R.id.btnMemoriesTab -> Tab.MEMORIES
                R.id.btnRulesTab -> Tab.RULES
                else -> Tab.SKILLS
            }
            reload()
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) reload()
    }

    private fun reload() {
        val items = when (tab) {
            Tab.SKILLS -> skills.listAll().map { skill ->
                ContextAssetItem(skill.id, skill.name, skill.enabled, ContextAssetItem.Kind.SKILL)
            }
            Tab.MEMORIES -> store.memories().map { memory ->
                ContextAssetItem(
                    memory.id,
                    memory.content.take(60),
                    memory.enabled,
                    ContextAssetItem.Kind.MEMORY,
                )
            }
            Tab.RULES -> ruleStore.rules().map { rule ->
                ContextAssetItem(rule.id, rule.title, rule.enabled, ContextAssetItem.Kind.RULE)
            }
        }
        adapter.submit(items)
        binding.contextAssetsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addCurrentAsset() {
        when (tab) {
            Tab.SKILLS -> startActivity(SkillEditorActivity.newIntent(this))
            Tab.MEMORIES -> startActivity(TextEditorActivity.newMemoryIntent(this))
            Tab.RULES -> startActivity(TextEditorActivity.newRuleIntent(this))
        }
    }

    private fun toggle(item: ContextAssetItem, enabled: Boolean) {
        runCatching {
            when (item.kind) {
                ContextAssetItem.Kind.SKILL -> skills.setEnabled(item.id, enabled)
                ContextAssetItem.Kind.MEMORY -> store.setMemoryEnabled(item.id, enabled)
                ContextAssetItem.Kind.RULE -> ruleStore.setEnabled(item.id, enabled)
            } ?: error("项目不存在")
        }.onFailure { showError(it.message ?: "状态更新失败") }
        reload()
    }

    private fun edit(item: ContextAssetItem) {
        when (item.kind) {
            ContextAssetItem.Kind.SKILL -> startActivity(SkillEditorActivity.intent(this, item.id))
            ContextAssetItem.Kind.MEMORY -> startActivity(TextEditorActivity.memoryIntent(this, item.id))
            ContextAssetItem.Kind.RULE -> startActivity(TextEditorActivity.ruleIntent(this, item.id))
        }
    }

    private fun confirmDelete(item: ContextAssetItem) {
        MaterialAlertDialogBuilder(this, R.style.Theme_VoiceAssistant_PreferenceDialog)
            .setTitle(R.string.context_asset_delete_confirm_title)
            .setMessage(getString(R.string.context_asset_delete_confirm_message, item.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.context_asset_delete) { _, _ ->
                when (item.kind) {
                    ContextAssetItem.Kind.SKILL -> skills.delete(item.id)
                    ContextAssetItem.Kind.MEMORY -> store.deleteMemory(item.id)
                    ContextAssetItem.Kind.RULE -> ruleStore.deleteRule(item.id)
                }
                reload()
            }
            .showLightDialog()
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

}

private data class ContextAssetItem(
    val id: String,
    val title: String,
    val enabled: Boolean,
    val kind: Kind,
) {
    enum class Kind { SKILL, MEMORY, RULE }
}

private class ContextAssetAdapter(
    private val onToggle: (ContextAssetItem, Boolean) -> Unit,
    private val onEdit: (ContextAssetItem) -> Unit,
    private val onDelete: (ContextAssetItem) -> Unit,
) : RecyclerView.Adapter<ContextAssetAdapter.Holder>() {
    private val items = mutableListOf<ContextAssetItem>()

    fun submit(values: List<ContextAssetItem>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_context_asset, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val toggle = view.findViewById<MaterialSwitch>(R.id.switchContextAsset)
        private val title = view.findViewById<TextView>(R.id.tvContextAssetTitle)
        private val edit = view.findViewById<ImageButton>(R.id.btnEditContextAsset)
        private val delete = view.findViewById<ImageButton>(R.id.btnDeleteContextAsset)

        fun bind(item: ContextAssetItem) {
            toggle.setOnCheckedChangeListener(null)
            toggle.visibility = View.VISIBLE
            toggle.isChecked = item.enabled
            title.text = item.title
            toggle.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
            edit.setOnClickListener { onEdit(item) }
            delete.setOnClickListener { onDelete(item) }
        }
    }
}
