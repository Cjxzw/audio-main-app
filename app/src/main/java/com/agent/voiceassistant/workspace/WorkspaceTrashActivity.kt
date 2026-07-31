package com.agent.voiceassistant.workspace

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.databinding.ActivityWorkspaceBinding
import com.agent.voiceassistant.ui.showLightDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

class WorkspaceTrashActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkspaceBinding
    private lateinit var repository: WorkspaceTrashRepository
    private lateinit var adapter: TrashAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspaceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = WorkspaceTrashRepository(this)
        adapter = TrashAdapter(::openEntry)
        binding.workspaceToolbar.title = getString(R.string.workspace_trash)
        binding.workspaceToolbar.setNavigationOnClickListener { finish() }
        binding.tvWorkspacePath.text = getString(R.string.workspace_trash_retention)
        binding.rvWorkspace.layoutManager = LinearLayoutManager(this)
        binding.rvWorkspace.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        repository.markSeen()
        reload()
    }

    private fun reload() {
        val entries = repository.list()
        adapter.submit(entries)
        binding.tvWorkspaceEmpty.text = getString(R.string.workspace_trash_empty)
        binding.tvWorkspaceEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEntry(entry: WorkspaceTrashRepository.TrashEntry) {
        val labels = buildList {
            if (repository.canPreview(entry)) add(getString(R.string.workspace_preview))
            if (!entry.isDirectory) add(getString(R.string.workspace_open_external))
            add(getString(R.string.workspace_restore))
            add(getString(R.string.workspace_delete_permanently))
        }
        MaterialAlertDialogBuilder(this, R.style.Theme_VoiceAssistant_PreferenceDialog)
            .setTitle(entry.name)
            .setItems(labels.toTypedArray()) { _, index ->
                when (labels[index]) {
                    getString(R.string.workspace_preview) -> preview(entry)
                    getString(R.string.workspace_open_external) -> openExternal(entry)
                    getString(R.string.workspace_restore) -> restore(entry)
                    getString(R.string.workspace_delete_permanently) -> confirmDelete(entry)
                }
            }
            .showLightDialog()
    }

    private fun preview(entry: WorkspaceTrashRepository.TrashEntry) {
        runCatching { repository.readPreview(entry) }
            .onSuccess { text ->
                MaterialAlertDialogBuilder(this, R.style.Theme_VoiceAssistant_PreferenceDialog)
                    .setTitle(entry.name)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .showLightDialog()
            }
            .onFailure { showError(it.message ?: "预览失败") }
    }

    private fun openExternal(entry: WorkspaceTrashRepository.TrashEntry) {
        val uri = repository.contentUri(entry.id)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, repository.mimeType(entry))
            clipData = ClipData.newUri(contentResolver, entry.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.workspace_open_external))) }
            .onFailure { showError(getString(R.string.workspace_no_handler)) }
    }

    private fun restore(entry: WorkspaceTrashRepository.TrashEntry) {
        runCatching { repository.restore(entry.id) }
            .onSuccess { path ->
                Toast.makeText(this, getString(R.string.workspace_restored, path), Toast.LENGTH_LONG).show()
                reload()
            }
            .onFailure { showError(it.message ?: "还原失败") }
    }

    private fun confirmDelete(entry: WorkspaceTrashRepository.TrashEntry) {
        MaterialAlertDialogBuilder(this, R.style.Theme_VoiceAssistant_PreferenceDialog)
            .setTitle(R.string.workspace_delete_confirm_title)
            .setMessage(getString(R.string.workspace_delete_confirm_message, entry.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.workspace_delete_permanently) { _, _ ->
                repository.deletePermanently(entry.id)
                reload()
            }
            .showLightDialog()
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private class TrashAdapter(
    private val onClick: (WorkspaceTrashRepository.TrashEntry) -> Unit,
) : RecyclerView.Adapter<TrashAdapter.Holder>() {
    private val entries = mutableListOf<WorkspaceTrashRepository.TrashEntry>()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    fun submit(values: List<WorkspaceTrashRepository.TrashEntry>) {
        entries.clear()
        entries.addAll(values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_workspace_entry, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(entries[position])
    override fun getItemCount() = entries.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.ivWorkspaceEntry)
        private val name = view.findViewById<TextView>(R.id.tvWorkspaceEntryName)
        private val metadata = view.findViewById<TextView>(R.id.tvWorkspaceEntryMeta)

        fun bind(entry: WorkspaceTrashRepository.TrashEntry) {
            icon.setImageDrawable(ContextCompat.getDrawable(itemView.context, if (entry.isDirectory) R.drawable.ic_folder_24 else R.drawable.ic_file_24))
            name.text = entry.name
            metadata.text = itemView.context.getString(
                R.string.workspace_trash_metadata,
                entry.originalPath,
                dateFormat.format(Date(entry.deletedAt)),
            )
            itemView.setOnClickListener { onClick(entry) }
        }
    }
}
