package com.agent.voiceassistant.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.data.StoredMemory
import com.agent.voiceassistant.databinding.ActivityContextAssetsBinding
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class ContextAssetsActivity : AppCompatActivity() {
    private enum class Tab { SKILLS, MEMORIES }

    private lateinit var binding: ActivityContextAssetsBinding
    private lateinit var store: ConversationStore
    private lateinit var skills: SkillRegistry
    private lateinit var adapter: ContextAssetAdapter
    private var tab = Tab.SKILLS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContextAssetsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ConversationStore(this)
        val env = AndroidExecutionEnv(this)
        skills = SkillRegistry(env.skillsRoot, env.disabledSkillsRoot, env.deletedSkillsManifest, env.modifiedSkillsManifest)
        adapter = ContextAssetAdapter(::toggle, ::edit, ::confirmDelete)
        binding.contextAssetsList.layoutManager = LinearLayoutManager(this)
        binding.contextAssetsList.adapter = adapter
        binding.contextAssetsToolbar.setNavigationOnClickListener { finish() }
        binding.contextAssetTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            tab = if (checkedId == R.id.btnMemoriesTab) Tab.MEMORIES else Tab.SKILLS
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
        }
        adapter.submit(items)
        binding.contextAssetsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggle(item: ContextAssetItem, enabled: Boolean) {
        runCatching {
            when (item.kind) {
                ContextAssetItem.Kind.SKILL -> skills.setEnabled(item.id, enabled)
                ContextAssetItem.Kind.MEMORY -> store.setMemoryEnabled(item.id, enabled)
            } ?: error("项目不存在")
        }.onFailure { showError(it.message ?: "状态更新失败") }
        reload()
    }

    private fun edit(item: ContextAssetItem) {
        when (item.kind) {
            ContextAssetItem.Kind.SKILL -> startActivity(SkillEditorActivity.intent(this, item.id))
            ContextAssetItem.Kind.MEMORY -> editMemory(item.id)
        }
    }

    private fun editMemory(id: String) {
        val memory = store.memories().firstOrNull { it.id == id } ?: return
        val content = editField(memory.content, multiline = true)
        val tags = editField(memory.tags.joinToString(", "))
        val form = form(content, tags)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.context_asset_edit_memory)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching {
                    require(content.text.toString().isNotBlank()) { "记忆不能为空" }
                    store.updateMemory(
                        id,
                        content.text.toString(),
                        tags.text.toString().split(',', '，').map(String::trim).filter(String::isNotBlank),
                    )
                }.onFailure { showError(it.message ?: "记忆保存失败") }
                reload()
            }
            .show()
    }

    private fun confirmDelete(item: ContextAssetItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.context_asset_delete_confirm_title)
            .setMessage(getString(R.string.context_asset_delete_confirm_message, item.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.context_asset_delete) { _, _ ->
                when (item.kind) {
                    ContextAssetItem.Kind.SKILL -> skills.delete(item.id)
                    ContextAssetItem.Kind.MEMORY -> store.deleteMemory(item.id)
                }
                reload()
            }
            .show()
    }

    private fun editField(value: String, multiline: Boolean = false) = EditText(this).apply {
        setText(value)
        inputType = if (multiline) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        minLines = if (multiline) 5 else 1
        maxLines = if (multiline) 14 else 3
    }

    private fun form(vararg fields: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * resources.displayMetrics.density).toInt())
        fields.forEach { field ->
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            })
        }
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private data class ContextAssetItem(
    val id: String,
    val title: String,
    val enabled: Boolean,
    val kind: Kind,
) {
    enum class Kind { SKILL, MEMORY }
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
            toggle.isChecked = item.enabled
            title.text = item.title
            toggle.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
            edit.setOnClickListener { onEdit(item) }
            delete.setOnClickListener { onDelete(item) }
        }
    }
}
