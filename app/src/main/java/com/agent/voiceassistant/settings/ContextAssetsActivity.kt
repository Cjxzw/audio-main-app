package com.agent.voiceassistant.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
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
    enum class Tab { SKILLS, MEMORIES, RULES }

    companion object {
        private const val EXTRA_TAB = "context_assets_tab"

        fun intent(context: android.content.Context, tab: Tab): android.content.Intent =
            android.content.Intent(context, ContextAssetsActivity::class.java)
                .putExtra(EXTRA_TAB, tab.name)
    }

    private lateinit var binding: ActivityContextAssetsBinding
    private lateinit var store: ConversationStore
    private lateinit var ruleStore: RuleStore
    private lateinit var skills: SkillRegistry
    private lateinit var adapter: ContextAssetAdapter
    private var tab = Tab.SKILLS
    private var allItems: List<ContextAssetItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContextAssetsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ConversationStore(this)
        ruleStore = RuleStore(this)
        val env = AndroidExecutionEnv(this)
        skills = SkillRegistry(env.skillsRoot, env.disabledSkillsRoot, env.deletedSkillsManifest, env.modifiedSkillsManifest, env.systemSkillsRoot, env.disabledSystemSkillsManifest)
        adapter = ContextAssetAdapter(::toggle, ::edit, ::confirmDelete)
        binding.contextAssetsList.layoutManager = LinearLayoutManager(this)
        binding.contextAssetsList.adapter = adapter
        binding.contextAssetsToolbar.inflateMenu(R.menu.menu_context_assets)
        binding.contextAssetsToolbar.setNavigationOnClickListener { finish() }
        tab = runCatching { Tab.valueOf(intent.getStringExtra(EXTRA_TAB).orEmpty()) }
            .getOrDefault(Tab.SKILLS)
        binding.contextAssetsToolbar.title = tabTitle(tab)
        binding.contextAssetsSearchLayout.hint = getString(R.string.context_assets_search_hint, tabTitle(tab))
        binding.contextAssetsToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_add_context_asset) {
                addCurrentAsset()
                true
            } else {
                false
            }
        }
        binding.contextAssetsSearch.doAfterTextChanged { applyFilter() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) reload()
    }

    private fun reload() {
        allItems = when (tab) {
            Tab.SKILLS -> skills.listAll().sortedByDescending { it.enabled }.map { skill ->
                ContextAssetItem(
                    skill.id, skill.name, skill.enabled, ContextAssetItem.Kind.SKILL, skill.system,
                    searchFields = listOf(skill.id, skill.name, skill.description, skill.virtualPath, skill.version),
                )
            }
            // UI browsing must remain read-only: searchMemories() awards recall points.
            Tab.MEMORIES -> store.memories().map { memory ->
                ContextAssetItem(
                    memory.id,
                    memory.content,
                    memory.enabled,
                    ContextAssetItem.Kind.MEMORY,
                    scoreText = getString(R.string.context_assets_memory_score_short, store.memoryScore(memory)),
                    searchFields = listOf(memory.id, memory.content) + memory.tags,
                )
            }
            Tab.RULES -> ruleStore.rules().sortedWith(
                compareByDescending<com.agent.voiceassistant.data.UserRule> { it.enabled }
                    .thenByDescending { it.updatedAt }.thenBy { it.title },
            ).map { rule ->
                ContextAssetItem(
                    rule.id, rule.title, rule.enabled, ContextAssetItem.Kind.RULE,
                    searchFields = listOf(rule.title, rule.body),
                )
            }
        }
        applyFilter()
    }

    private fun applyFilter() {
        val query = binding.contextAssetsSearch.text?.toString().orEmpty()
        val items = allItems.filter { ContextAssetSearch.matches(query, it.searchFields) }
        adapter.submit(items)
        binding.contextAssetsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.contextAssetsEmpty.setText(if (query.isBlank()) R.string.context_assets_empty else R.string.context_assets_no_matches)
        binding.contextAssetsCount.text = getString(R.string.context_assets_result_count, items.size, allItems.size)
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
        if (item.system) return
        when (item.kind) {
            ContextAssetItem.Kind.SKILL -> startActivity(SkillEditorActivity.intent(this, item.id))
            ContextAssetItem.Kind.MEMORY -> startActivity(TextEditorActivity.memoryIntent(this, item.id))
            ContextAssetItem.Kind.RULE -> startActivity(TextEditorActivity.ruleIntent(this, item.id))
        }
    }

    private fun confirmDelete(item: ContextAssetItem) {
        if (item.system) return
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

    private fun tabTitle(tab: Tab): String = when (tab) {
        Tab.SKILLS -> getString(R.string.context_assets_skills)
        Tab.MEMORIES -> getString(R.string.context_assets_memories)
        Tab.RULES -> getString(R.string.context_assets_rules)
    }

}

private data class ContextAssetItem(
    val id: String,
    val title: String,
    val enabled: Boolean,
    val kind: Kind,
    val system: Boolean = false,
    val scoreText: String = "",
    val searchFields: List<String> = emptyList(),
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
        private val title = view.findViewById<TextView>(R.id.tvContextAssetTitle)
        private val score = view.findViewById<TextView>(R.id.tvContextAssetScore)
        private val toggle = view.findViewById<MaterialSwitch>(R.id.switchContextAsset)
        private val edit = view.findViewById<ImageButton>(R.id.btnEditContextAsset)
        private val delete = view.findViewById<ImageButton>(R.id.btnDeleteContextAsset)

        fun bind(item: ContextAssetItem) {
            title.text = item.title
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            val memory = item.kind == ContextAssetItem.Kind.MEMORY
            score.text = item.scoreText
            score.visibility = if (memory) View.VISIBLE else View.GONE
            toggle.visibility = if (memory) View.GONE else View.VISIBLE
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = item.enabled
            toggle.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
            edit.visibility = if (memory || item.system) View.GONE else View.VISIBLE
            delete.visibility = if (memory || item.system) View.GONE else View.VISIBLE
            edit.setOnClickListener { onEdit(item) }
            delete.setOnClickListener { onDelete(item) }
            itemView.setOnClickListener { if (memory) onEdit(item) }
        }
    }
}
