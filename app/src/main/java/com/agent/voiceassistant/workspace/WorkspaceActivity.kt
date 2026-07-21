package com.agent.voiceassistant.workspace

import android.content.Intent
import android.content.ClipData
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.databinding.ActivityWorkspaceBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

class WorkspaceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkspaceBinding
    private lateinit var repository: WorkspaceRepository
    private lateinit var adapter: WorkspaceAdapter
    private var currentDirectory = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspaceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = WorkspaceRepository(this)
        adapter = WorkspaceAdapter(::openEntry)
        binding.rvWorkspace.layoutManager = LinearLayoutManager(this)
        binding.rvWorkspace.adapter = adapter
        binding.workspaceToolbar.setNavigationOnClickListener { navigateBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        runCatching { repository.list(currentDirectory) }
            .onSuccess { entries ->
                binding.tvWorkspacePath.text = "/workspace${currentDirectory.takeIf(String::isNotBlank)?.let { "/$it" }.orEmpty()}"
                binding.tvWorkspaceEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                adapter.submit(entries)
            }
            .onFailure { showError(it.message ?: "工作区读取失败") }
    }

    private fun openEntry(entry: WorkspaceRepository.Entry) {
        if (entry.isDirectory) {
            currentDirectory = entry.relativePath
            reload()
            return
        }
        val labels = buildList {
            if (repository.canPreview(entry.relativePath)) add(getString(R.string.workspace_preview))
            add(getString(R.string.workspace_open_external))
            add(getString(R.string.workspace_share))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(labels.toTypedArray()) { _, index ->
                val action = labels[index]
                when (action) {
                    getString(R.string.workspace_preview) -> startActivity(
                        Intent(this, WorkspacePreviewActivity::class.java)
                            .putExtra(WorkspacePreviewActivity.EXTRA_PATH, entry.relativePath),
                    )
                    getString(R.string.workspace_open_external) -> openExternal(entry)
                    getString(R.string.workspace_share) -> share(entry)
                }
            }
            .show()
    }

    private fun openExternal(entry: WorkspaceRepository.Entry) {
        val uri = repository.contentUri(entry.relativePath)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, repository.mimeType(entry.relativePath))
            clipData = ClipData.newUri(contentResolver, entry.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.workspace_open_external))) }
            .onFailure { showError(getString(R.string.workspace_no_handler)) }
    }

    private fun share(entry: WorkspaceRepository.Entry) {
        val uri = repository.contentUri(entry.relativePath)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = repository.mimeType(entry.relativePath)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, entry.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.workspace_share))) }
            .onFailure { showError(getString(R.string.workspace_no_handler)) }
    }

    private fun navigateBack() {
        if (currentDirectory.isBlank()) {
            finish()
        } else {
            currentDirectory = currentDirectory.substringBeforeLast('/', "")
            reload()
        }
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private class WorkspaceAdapter(
    private val onClick: (WorkspaceRepository.Entry) -> Unit,
) : RecyclerView.Adapter<WorkspaceAdapter.Holder>() {
    private val entries = mutableListOf<WorkspaceRepository.Entry>()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    fun submit(values: List<WorkspaceRepository.Entry>) {
        entries.clear()
        entries.addAll(values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_workspace_entry, parent, false),
    )
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(entries[position])
    override fun getItemCount(): Int = entries.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.ivWorkspaceEntry)
        private val name = view.findViewById<TextView>(R.id.tvWorkspaceEntryName)
        private val metadata = view.findViewById<TextView>(R.id.tvWorkspaceEntryMeta)

        fun bind(entry: WorkspaceRepository.Entry) {
            icon.setImageDrawable(ContextCompat.getDrawable(itemView.context, if (entry.isDirectory) R.drawable.ic_folder_24 else R.drawable.ic_file_24))
            name.text = entry.name
            metadata.text = if (entry.isDirectory) {
                itemView.context.getString(R.string.workspace_folder)
            } else {
                "${formatBytes(entry.size)} · ${dateFormat.format(Date(entry.modifiedAt))}"
            }
            itemView.setOnClickListener { onClick(entry) }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes < 1_024 -> "$bytes B"
            bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
            else -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
        }
    }
}
