package com.agent.voiceassistant.editor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.agent.voiceassistant.R
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import com.agent.voiceassistant.databinding.ActivityTextEditorBinding
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import com.agent.voiceassistant.workspace.WorkspaceRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.util.Locale

class TextEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTextEditorBinding
    private lateinit var workspace: WorkspaceRepository
    private lateinit var skills: SkillRegistry
    private lateinit var markwon: Markwon
    private lateinit var source: Source
    private lateinit var path: String
    private var skillId: String? = null
    private var savedText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        workspace = WorkspaceRepository(this)
        val env = AndroidExecutionEnv(this)
        skills = SkillRegistry(env.skillsRoot, env.disabledSkillsRoot, env.deletedSkillsManifest, env.modifiedSkillsManifest)
        markwon = Markwon.builder(this).usePlugin(TablePlugin.create(this)).build()

        source = Source.entries.firstOrNull { it.wireValue == intent.getStringExtra(EXTRA_SOURCE) }
            ?: return fail("缺少编辑来源")
        path = intent.getStringExtra(EXTRA_PATH)?.takeIf(String::isNotBlank)
            ?: return fail("缺少文件路径")
        skillId = intent.getStringExtra(EXTRA_SKILL_ID)

        binding.textEditorToolbar.title = path.substringAfterLast('/')
        binding.textEditorToolbar.inflateMenu(R.menu.menu_text_editor)
        binding.textEditorToolbar.setNavigationOnClickListener { leaveEditor() }
        binding.textEditorToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save_text) {
                save()
                true
            } else {
                false
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveEditor()
        })

        val markdown = path.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("md", "markdown")
        binding.textEditorModes.visibility = if (markdown) View.VISIBLE else View.GONE
        binding.textEditorModes.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showPreview(checkedId == R.id.btnEditorPreview)
        }

        runCatching { readText() }
            .onSuccess { text ->
                savedText = text
                binding.textEditorInput.setText(text)
                binding.textEditorInput.setSelection(text.length)
                binding.textEditorInput.doAfterTextChanged { updateSaveState() }
                updateSaveState()
            }
            .onFailure { fail(it.message ?: "文件读取失败") }
    }

    private fun readText(): String = when (source) {
        Source.WORKSPACE -> workspace.readEditable(path)
        Source.SKILL -> skills.readFile(requireNotNull(skillId), path)
    }

    private fun save() {
        val text = binding.textEditorInput.text?.toString().orEmpty()
        runCatching {
            when (source) {
                Source.WORKSPACE -> workspace.saveText(path, text)
                Source.SKILL -> skills.updateFile(requireNotNull(skillId), path, text)
            }
        }.onSuccess {
            savedText = text
            updateSaveState()
            Toast.makeText(this, R.string.text_editor_saved, Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "保存失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPreview(preview: Boolean) {
        binding.textEditorInput.visibility = if (preview) View.GONE else View.VISIBLE
        binding.textEditorPreviewScroll.visibility = if (preview) View.VISIBLE else View.GONE
        if (preview) {
            markwon.setMarkdown(binding.textEditorPreview, binding.textEditorInput.text?.toString().orEmpty())
        }
    }

    private fun updateSaveState() {
        binding.textEditorToolbar.menu.findItem(R.id.action_save_text)?.isEnabled = isDirty()
    }

    private fun isDirty(): Boolean = binding.textEditorInput.text?.toString().orEmpty() != savedText

    private fun leaveEditor() {
        if (!isDirty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_editor_discard_title)
            .setMessage(R.string.text_editor_discard_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.text_editor_discard) { _, _ -> finish() }
            .show()
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private enum class Source(val wireValue: String) {
        WORKSPACE("workspace"),
        SKILL("skill"),
    }

    companion object {
        private const val EXTRA_SOURCE = "editor_source"
        private const val EXTRA_PATH = "editor_path"
        private const val EXTRA_SKILL_ID = "editor_skill_id"

        fun workspaceIntent(context: Context, path: String) = Intent(context, TextEditorActivity::class.java)
            .putExtra(EXTRA_SOURCE, Source.WORKSPACE.wireValue)
            .putExtra(EXTRA_PATH, path)

        fun skillIntent(context: Context, skillId: String, path: String) = Intent(context, TextEditorActivity::class.java)
            .putExtra(EXTRA_SOURCE, Source.SKILL.wireValue)
            .putExtra(EXTRA_SKILL_ID, skillId)
            .putExtra(EXTRA_PATH, path)
    }
}
