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
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.data.RuleStore
import com.agent.voiceassistant.data.StoredMemory
import com.agent.voiceassistant.data.UserRule
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import com.agent.voiceassistant.workspace.WorkspaceRepository
import com.agent.voiceassistant.ui.showLightDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.util.Locale

class TextEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTextEditorBinding
    private lateinit var workspace: WorkspaceRepository
    private lateinit var skills: SkillRegistry
    private lateinit var markwon: Markwon
    private lateinit var conversationStore: ConversationStore
    private lateinit var ruleStore: RuleStore
    private lateinit var source: Source
    private lateinit var path: String
    private var skillId: String? = null
    private var savedText = ""
    private var savedTags = ""
    private var savedRuleTitle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        workspace = WorkspaceRepository(this)
        conversationStore = ConversationStore(this)
        ruleStore = RuleStore(this)
        val env = AndroidExecutionEnv(this)
        skills = SkillRegistry(env.skillsRoot, env.disabledSkillsRoot, env.deletedSkillsManifest, env.modifiedSkillsManifest)
        markwon = Markwon.builder(this).usePlugin(TablePlugin.create(this)).build()

        source = Source.entries.firstOrNull { it.wireValue == intent.getStringExtra(EXTRA_SOURCE) }
            ?: return fail("缺少编辑来源")
        path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        if (path.isBlank() && source !in setOf(Source.MEMORY, Source.RULE)) return fail("缺少文件路径")
        skillId = intent.getStringExtra(EXTRA_SKILL_ID)

        binding.textEditorToolbar.title = when (source) {
            Source.MEMORY -> if (creatingMemory()) getString(R.string.context_assets_add_memory) else getString(R.string.context_asset_edit_memory)
            Source.RULE -> if (creatingRule()) getString(R.string.context_assets_add_rule) else getString(R.string.context_asset_edit_rule)
            else -> path.substringAfterLast('/')
        }
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

        val markdown = source == Source.MEMORY || source == Source.RULE ||
            path.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("md", "markdown")
        binding.textEditorModes.visibility = if (markdown) View.VISIBLE else View.GONE
        binding.memoryTagsLayout.visibility = if (source == Source.MEMORY) View.VISIBLE else View.GONE
        binding.ruleTitleLayout.visibility = if (source == Source.RULE) View.VISIBLE else View.GONE
        binding.textEditorModes.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showPreview(checkedId == R.id.btnEditorPreview)
        }

        runCatching { readText() }
            .onSuccess { text ->
                savedText = text
                if (source == Source.MEMORY) {
                    savedTags = if (creatingMemory()) "" else requireMemory().tags.joinToString(", ")
                    binding.memoryTagsInput.setText(savedTags)
                    binding.memoryTagsInput.doAfterTextChanged { updateSaveState() }
                }
                if (source == Source.RULE) {
                    savedRuleTitle = if (creatingRule()) "" else requireRule().title
                    binding.ruleTitleInput.setText(savedRuleTitle)
                    binding.ruleTitleInput.doAfterTextChanged { updateSaveState() }
                }
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
        Source.MEMORY -> if (creatingMemory()) "" else requireMemory().content
        Source.RULE -> if (creatingRule()) "" else requireRule().body
    }

    private fun save() {
        val text = binding.textEditorInput.text?.toString().orEmpty()
        var savedMemory: StoredMemory? = null
        var savedRule: UserRule? = null
        runCatching {
            when (source) {
                Source.WORKSPACE -> workspace.saveText(path, text)
                Source.SKILL -> skills.updateFile(requireNotNull(skillId), path, text)
                Source.MEMORY -> {
                    require(text.isNotBlank()) { "记忆不能为空" }
                    val tags = binding.memoryTagsInput.text?.toString().orEmpty()
                        .split(',', '，')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    savedMemory = if (creatingMemory()) {
                        conversationStore.addMemory(text, tags)
                    } else {
                        conversationStore.updateMemory(path, text, tags) ?: error("记忆不存在")
                    }
                    path = requireNotNull(savedMemory).id
                }
                Source.RULE -> {
                    val title = binding.ruleTitleInput.text?.toString().orEmpty()
                    savedRule = if (creatingRule()) {
                        ruleStore.createRule(title, text)
                    } else {
                        ruleStore.updateRule(path, title, text) ?: error("规则不存在")
                    }
                    path = requireNotNull(savedRule).id
                }
            }
        }.onSuccess {
            savedText = savedRule?.body ?: savedMemory?.content ?: text
            savedTags = savedMemory?.tags?.joinToString(", ")
                ?: binding.memoryTagsInput.text?.toString().orEmpty()
            savedRuleTitle = savedRule?.title ?: savedRuleTitle
            savedRule?.let { rule ->
                binding.ruleTitleInput.setText(rule.title)
                binding.textEditorInput.setText(rule.body)
                binding.textEditorInput.setSelection(rule.body.length)
                binding.textEditorToolbar.title = rule.title
            }
            savedMemory?.let { memory ->
                binding.memoryTagsInput.setText(savedTags)
                binding.textEditorInput.setText(memory.content)
                binding.textEditorInput.setSelection(memory.content.length)
                binding.textEditorToolbar.title = getString(R.string.context_asset_edit_memory)
            }
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

    private fun isDirty(): Boolean =
        binding.textEditorInput.text?.toString().orEmpty() != savedText ||
            (source == Source.MEMORY && binding.memoryTagsInput.text?.toString().orEmpty() != savedTags) ||
            (source == Source.RULE && binding.ruleTitleInput.text?.toString().orEmpty() != savedRuleTitle)

    private fun requireMemory() = conversationStore.memories().firstOrNull { it.id == path }
        ?: error("记忆不存在")

    private fun requireRule() = ruleStore.rules().firstOrNull { it.id == path }
        ?: error("规则不存在")

    private fun creatingRule(): Boolean = source == Source.RULE && path.isBlank()

    private fun creatingMemory(): Boolean = source == Source.MEMORY && path.isBlank()

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

    private enum class Source(val wireValue: String) {
        WORKSPACE("workspace"),
        SKILL("skill"),
        MEMORY("memory"),
        RULE("rule"),
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

        fun memoryIntent(context: Context, memoryId: String) = Intent(context, TextEditorActivity::class.java)
            .putExtra(EXTRA_SOURCE, Source.MEMORY.wireValue)
            .putExtra(EXTRA_PATH, memoryId)

        fun newMemoryIntent(context: Context) = Intent(context, TextEditorActivity::class.java)
            .putExtra(EXTRA_SOURCE, Source.MEMORY.wireValue)

        fun ruleIntent(context: Context, ruleId: String) = Intent(context, TextEditorActivity::class.java)
            .putExtra(EXTRA_SOURCE, Source.RULE.wireValue)
            .putExtra(EXTRA_PATH, ruleId)

        fun newRuleIntent(context: Context) = Intent(context, TextEditorActivity::class.java)
            .putExtra(EXTRA_SOURCE, Source.RULE.wireValue)
    }
}
