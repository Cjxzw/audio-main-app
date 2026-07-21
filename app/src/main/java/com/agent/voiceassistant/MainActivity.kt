package com.agent.voiceassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.databinding.ActivityMainBinding
import com.agent.voiceassistant.media.MainMediaLibraryService
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.service.ServiceState
import com.agent.voiceassistant.service.VoiceAgentService
import com.agent.voiceassistant.settings.SettingsActivity
import com.agent.voiceassistant.ui.ChatAdapter
import com.agent.voiceassistant.workspace.WorkspaceRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ConversationStore
    private val chatAdapter = ChatAdapter()
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private lateinit var workspace: WorkspaceRepository
    private val pendingAttachments = mutableListOf<WorkspaceRepository.Entry>()
    private var pendingCameraFile: File? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importUris(uris)
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        importUris(uris)
    }

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val target = pendingCameraFile
        pendingCameraFile = null
        if (target == null) return@registerForActivityResult
        if (saved) {
            runCatching { workspace.finalizeCameraTarget(target) }
                .onSuccess { addPendingAttachments(listOf(it)) }
                .onFailure { appendLog(it.message ?: "照片保存失败") }
        } else {
            workspace.discardCameraTarget(target)
        }
    }

    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val coreGranted = result.entries
            .filterNot { it.key == Manifest.permission.ACCESS_COARSE_LOCATION || it.key == Manifest.permission.ACCESS_FINE_LOCATION }
            .all { it.value }
        if (coreGranted) {
            Timber.i("Permissions granted")
            startAgentService()
        } else {
            appendLog("权限被拒绝，无法启动")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            VoiceAgentService.bootstrap(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConversationStore(this)
        workspace = WorkspaceRepository(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MainMediaLibraryService.ensureStarted(this)
        ensureNotificationPermissionAndBootstrap()

        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            itemAnimator = DefaultItemAnimator().apply { addDuration = 150 }
        }
        loadPersistedChat()

        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_attach -> {
                    showAttachmentMenu()
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.btnToggle.setOnClickListener {
            val isListening = binding.btnToggle.text == getString(R.string.btn_stop)
            if (isListening) {
                VoiceAgentService.stop(this)
                binding.btnToggle.text = getString(R.string.btn_start)
            } else {
                ensurePermissionsAndStart()
            }
        }

        binding.btnClear.setOnClickListener {
            logLines.clear()
            binding.tvLog.text = ""
        }

        binding.btnSendText.setOnClickListener {
            sendTextInput()
        }
        binding.etTextInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextInput()
                true
            } else {
                false
            }
        }

        observeEventBus()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun loadPersistedChat() {
        val messages = store.recentChatMessages()
        chatAdapter.setMessages(messages)
        if (messages.isNotEmpty()) {
            binding.rvChat.post { binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1) }
        }
    }

    private fun sendTextInput() {
        val text = binding.etTextInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank() && pendingAttachments.isEmpty()) return
        val attachments = pendingAttachments.map(WorkspaceRepository.Entry::virtualPath)
        val effectiveText = text.ifBlank { "请查看这些附件。" }
        binding.etTextInput.setText("")
        pendingAttachments.clear()
        updatePendingAttachments()
        VoiceAgentService.sendText(this, effectiveText, attachments)
    }

    private fun showAttachmentMenu() {
        val labels = arrayOf(
            getString(R.string.attachment_files),
            getString(R.string.attachment_photos),
            getString(R.string.attachment_camera),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.attachment_add)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> filePicker.launch(arrayOf("*/*"))
                    1 -> photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    2 -> runCatching { workspace.createCameraTarget() }
                        .onSuccess { (file, uri) ->
                            pendingCameraFile = file
                            camera.launch(uri)
                        }
                        .onFailure { appendLog(it.message ?: "无法启动相机") }
                }
            }
            .show()
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.take(MAX_ATTACHMENTS_PER_TURN).mapNotNull { uri ->
                    runCatching { workspace.importUri(uri).entry }
                        .onFailure { Timber.w(it, "Workspace import failed uri=$uri") }
                        .getOrNull()
                }
            }
            if (imported.isNotEmpty()) addPendingAttachments(imported)
            if (imported.size < uris.size) appendLog("部分附件未能导入或超过数量限制")
        }
    }

    private fun addPendingAttachments(entries: List<WorkspaceRepository.Entry>) {
        val available = (MAX_ATTACHMENTS_PER_TURN - pendingAttachments.size).coerceAtLeast(0)
        pendingAttachments += entries.take(available)
        updatePendingAttachments()
        appendLog(getString(R.string.attachment_imported, entries.joinToString { it.virtualPath }))
    }

    private fun updatePendingAttachments() {
        binding.tvPendingAttachments.visibility = if (pendingAttachments.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvPendingAttachments.text = getString(
            R.string.attachment_pending,
            pendingAttachments.joinToString { it.name },
        )
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)) return
        val uris = buildList {
            intent.data?.let(::add)
            intent.clipData?.let { clip ->
                repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
            }
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(::add)
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
        }.distinct()
        importUris(uris)
        intent.action = null
    }

    private fun ensurePermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startAgentService()
        } else {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureNotificationPermissionAndBootstrap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            VoiceAgentService.bootstrap(this)
        }
    }

    private fun startAgentService() {
        appendLog("正在启动 Agent…")
        VoiceAgentService.start(this)
        binding.btnToggle.text = getString(R.string.btn_stop)
    }

    private fun observeEventBus() {
        lifecycleScope.launch {
            EventBus.logs.collectLatest { log ->
                appendLog(log.message)
            }
        }
        lifecycleScope.launch {
            EventBus.states.collectLatest { state ->
                updateStateDisplay(state)
            }
        }
        lifecycleScope.launch {
            EventBus.chatMessages.collectLatest { msg ->
                chatAdapter.addMessage(msg)
                binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                Timber.d("UI chat: [${msg.role}] ${msg.text}")
            }
        }
        lifecycleScope.launch {
            EventBus.chatResets.collectLatest { messages ->
                chatAdapter.setMessages(messages)
                if (messages.isNotEmpty()) {
                    binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                }
            }
        }
        lifecycleScope.launch {
            EventBus.volumeEvents.collectLatest { level ->
                binding.voiceBar.setLevel(level)
            }
        }
    }

    private fun updateStateDisplay(state: ServiceState) {
        if (state == ServiceState.LISTENING) {
            binding.btnToggle.text = getString(R.string.btn_stop)
        } else if (state == ServiceState.IDLE || state == ServiceState.DORMANT || state == ServiceState.FAILED) {
            binding.btnToggle.text = getString(R.string.btn_start)
        }
    }

    private fun appendLog(msg: String) {
        val time = timeFmt.format(Date())
        val compact = msg
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .let { if (it.length > MAX_LOG_LINE_CHARS) it.take(MAX_LOG_LINE_CHARS) + "…" else it }
        logLines.addLast("[$time] $compact")
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeFirst()
        }
        binding.tvLog.text = logLines.joinToString("\n")
        binding.svLog.post { binding.svLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private companion object {
        private const val MAX_LOG_LINES = 40
        private const val MAX_LOG_LINE_CHARS = 180
        private const val MAX_ATTACHMENTS_PER_TURN = 6
    }
}
