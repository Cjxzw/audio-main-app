package com.agent.voiceassistant.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import com.agent.voiceassistant.databinding.ActivitySkillEditorBinding
import com.agent.voiceassistant.editor.TextEditorActivity
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import com.agent.voiceassistant.ui.showLightDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

class SkillEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillEditorBinding
    private lateinit var skills: SkillRegistry
    private lateinit var skillId: String
    private lateinit var adapter: SkillFileAdapter
    private var creating = false
    private var savedName = ""
    private var savedDescription = ""
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        creating = intent.getBooleanExtra(EXTRA_CREATE, false)
        skillId = intent.getStringExtra(EXTRA_SKILL_ID).orEmpty()
        if (!creating && skillId.isBlank()) return fail("缺少 Skill ID")
        val env = AndroidExecutionEnv(this)
        skills = SkillRegistry(env.skillsRoot, env.disabledSkillsRoot, env.deletedSkillsManifest, env.modifiedSkillsManifest)
        adapter = SkillFileAdapter(::openFile)
        binding.skillFilesList.layoutManager = LinearLayoutManager(this)
        binding.skillFilesList.adapter = adapter
        binding.skillEditorToolbar.title = if (creating) {
            getString(R.string.context_assets_add_skill)
        } else {
            getString(R.string.context_asset_edit_skill)
        }

        binding.skillEditorToolbar.inflateMenu(R.menu.menu_text_editor)
        binding.skillEditorToolbar.setNavigationOnClickListener { leaveEditor() }
        binding.skillEditorToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save_text) {
                saveMetadata()
                true
            } else {
                false
            }
        }
        binding.skillNameInput.doAfterTextChanged { updateSaveState() }
        binding.skillDescriptionInput.doAfterTextChanged { updateSaveState() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveEditor()
        })
    }

    override fun onResume() {
        super.onResume()
        if (creating) {
            adapter.submit(emptyList())
            updateSaveState()
            return
        }
        reload(reloadMetadata = !loaded || !isDirty())
        loaded = true
    }

    private fun reload(reloadMetadata: Boolean) {
        runCatching {
            val skill = skills.listAll().firstOrNull { it.id == skillId } ?: error("Skill 不存在")
            if (reloadMetadata) {
                savedName = skill.name
                savedDescription = skill.description
                binding.skillNameInput.setText(savedName)
                binding.skillDescriptionInput.setText(savedDescription)
                binding.skillEditorToolbar.subtitle = skill.virtualPath.substringBeforeLast('/')
            }
            adapter.submit(skills.files(skillId))
        }.onFailure { fail(it.message ?: "Skill 读取失败") }
        updateSaveState()
    }

    private fun saveMetadata() {
        val name = binding.skillNameInput.text?.toString().orEmpty().trim()
        val description = binding.skillDescriptionInput.text?.toString().orEmpty().trim()
        runCatching {
            if (creating) skills.create(name, description) else skills.updateMetadata(skillId, name, description)
        }
            .onSuccess {
                skillId = it.id
                creating = false
                savedName = it.name
                savedDescription = it.description
                binding.skillNameInput.setText(savedName)
                binding.skillDescriptionInput.setText(savedDescription)
                binding.skillEditorToolbar.title = getString(R.string.context_asset_edit_skill)
                reload(reloadMetadata = true)
                updateSaveState()
                Toast.makeText(this, R.string.text_editor_saved, Toast.LENGTH_SHORT).show()
            }
            .onFailure { error -> Toast.makeText(this, error.message ?: "保存失败", Toast.LENGTH_LONG).show() }
    }

    private fun openFile(file: SkillRegistry.SkillFile) {
        if (!file.editable) {
            Toast.makeText(this, R.string.skill_editor_read_only, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(TextEditorActivity.skillIntent(this, skillId, file.relativePath))
    }

    private fun isDirty(): Boolean =
        binding.skillNameInput.text?.toString().orEmpty().trim() != savedName ||
            binding.skillDescriptionInput.text?.toString().orEmpty().trim() != savedDescription

    private fun updateSaveState() {
        binding.skillEditorToolbar.menu.findItem(R.id.action_save_text)?.isEnabled = isDirty()
    }

    private fun leaveEditor() {
        if (!isDirty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this, R.style.Theme_VoiceAssistant_PreferenceDialog)
            .setTitle(R.string.text_editor_discard_title)
            .setMessage(R.string.text_editor_discard_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.text_editor_discard) { _, _ -> finish() }
            .showLightDialog()
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val EXTRA_SKILL_ID = "skill_id"
        private const val EXTRA_CREATE = "create_skill"

        fun intent(context: Context, skillId: String) = Intent(context, SkillEditorActivity::class.java)
            .putExtra(EXTRA_SKILL_ID, skillId)

        fun newIntent(context: Context) = Intent(context, SkillEditorActivity::class.java)
            .putExtra(EXTRA_CREATE, true)
    }
}

private class SkillFileAdapter(
    private val onClick: (SkillRegistry.SkillFile) -> Unit,
) : RecyclerView.Adapter<SkillFileAdapter.Holder>() {
    private val files = mutableListOf<SkillRegistry.SkillFile>()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    fun submit(values: List<SkillRegistry.SkillFile>) {
        files.clear()
        files.addAll(values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_skill_file, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(files[position])
    override fun getItemCount(): Int = files.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.ivSkillFile)
        private val path = view.findViewById<TextView>(R.id.tvSkillFilePath)
        private val metadata = view.findViewById<TextView>(R.id.tvSkillFileMeta)

        fun bind(file: SkillRegistry.SkillFile) {
            path.text = file.relativePath
            metadata.text = itemView.context.getString(
                R.string.skill_editor_file_meta,
                formatBytes(file.size),
                dateFormat.format(Date(file.modifiedAt)),
            )
            icon.alpha = if (file.editable) 1f else 0.45f
            path.alpha = if (file.editable) 1f else 0.55f
            itemView.setOnClickListener { onClick(file) }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes < 1_024 -> "$bytes B"
            bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
            else -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
        }
    }
}
