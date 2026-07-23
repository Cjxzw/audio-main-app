package com.agent.voiceassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.data.ConversationSummary
import com.agent.voiceassistant.databinding.ActivityMainBinding
import com.agent.voiceassistant.media.MainMediaLibraryService
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.service.ServiceState
import com.agent.voiceassistant.service.VoiceAgentService
import com.agent.voiceassistant.settings.SettingsActivity
import com.agent.voiceassistant.ui.ChatAdapter
import com.agent.voiceassistant.ui.ConversationAdapter
import com.agent.voiceassistant.workspace.WorkspaceRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ConversationStore
    private val chatAdapter = ChatAdapter()
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var workspace: WorkspaceRepository
    private val pendingAttachments = mutableListOf<WorkspaceRepository.Entry>()
    private var pendingCameraFile: File? = null
    private var agentListening = false
    private var pendingLegacyShareUris: List<Uri> = emptyList()

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
                .onFailure { showMessage(it.message ?: "照片保存失败") }
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
            showMessage("权限被拒绝，无法启动")
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
        conversationAdapter = ConversationAdapter(
            onSelect = { conversation -> selectConversation(conversation) },
            onMore = { anchor, conversation -> showConversationMenu(anchor, conversation) },
        )
        binding.rvConversations.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = conversationAdapter
        }
        loadPersistedChat()
        refreshConversations()

        binding.topAppBar.setNavigationOnClickListener {
            refreshConversations()
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_voice_call -> {
                    toggleVoiceSession()
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.btnAttach.setOnClickListener { showAttachmentMenu() }
        binding.btnNewConversation.setOnClickListener {
            showMessage(getString(R.string.conversation_new_preparing))
            VoiceAgentService.newConversation(this)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        TooltipCompat.setTooltipText(binding.btnAttach, getString(R.string.attachment_add))
        TooltipCompat.setTooltipText(binding.btnSendText, getString(R.string.btn_send_text))
        TooltipCompat.setTooltipText(binding.btnNewConversation, getString(R.string.conversation_new))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

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

    private val legacySharePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val uris = pendingLegacyShareUris
        pendingLegacyShareUris = emptyList()
        if (granted) {
            importUris(uris)
        } else {
            showMessage("无法读取分享图片，请允许喊我访问照片，或从系统相册重新分享")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshConversations()
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
                        .onFailure { showMessage(it.message ?: "无法启动相机") }
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
            if (imported.size < uris.size) showMessage("部分附件未能导入或超过数量限制")
        }
    }

    private fun addPendingAttachments(entries: List<WorkspaceRepository.Entry>) {
        val available = (MAX_ATTACHMENTS_PER_TURN - pendingAttachments.size).coerceAtLeast(0)
        pendingAttachments += entries.take(available)
        updatePendingAttachments()
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
        val legacyFileUris = uris.filter { it.scheme == "file" }
        val readableUris = uris.filterNot { it.scheme == "file" }
        if (readableUris.isNotEmpty()) importUris(readableUris)
        if (legacyFileUris.isNotEmpty()) {
            if (hasLegacySharePermission()) {
                importUris(legacyFileUris)
            } else {
                pendingLegacyShareUris = legacyFileUris
                legacySharePermissionLauncher.launch(legacySharePermission())
            }
        }
        intent.action = null
    }

    private fun legacySharePermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun hasLegacySharePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, legacySharePermission()) == PackageManager.PERMISSION_GRANTED

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
        setVoiceControlActive(true)
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
                refreshConversations()
                Timber.d("UI chat: [${msg.role}] ${msg.text}")
            }
        }
        lifecycleScope.launch {
            EventBus.chatResets.collectLatest { messages ->
                chatAdapter.setMessages(messages)
                if (messages.isNotEmpty()) {
                    binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                }
                refreshConversations()
            }
        }
        lifecycleScope.launch {
            EventBus.conversationUpdates.collectLatest {
                refreshConversations()
            }
        }
        lifecycleScope.launch {
            EventBus.userNotices.collectLatest(::showMessage)
        }
        lifecycleScope.launch {
            EventBus.volumeEvents.collectLatest { level ->
                binding.inputVoiceBar.setLevel(level)
            }
        }
    }

    private fun updateStateDisplay(state: ServiceState) {
        if (state == ServiceState.LISTENING) {
            setVoiceControlActive(true)
        } else if (state == ServiceState.IDLE || state == ServiceState.DORMANT || state == ServiceState.FAILED) {
            setVoiceControlActive(false)
        }
    }

    private fun appendLog(msg: String) {
        Timber.i("UI: %s", msg.replace('\n', ' ').take(240))
    }

    private fun toggleVoiceSession() {
        if (agentListening) {
            VoiceAgentService.stop(this)
            setVoiceControlActive(false)
        } else {
            ensurePermissionsAndStart()
        }
    }

    private fun setVoiceControlActive(active: Boolean) {
        agentListening = active
        binding.etTextInput.isEnabled = !active
        binding.etTextInput.visibility = if (active) View.GONE else View.VISIBLE
        binding.inputVoiceBar.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnAttach.isEnabled = !active
        binding.btnSendText.isEnabled = !active
        binding.btnAttach.alpha = if (active) 0.35f else 1f
        binding.btnSendText.alpha = if (active) 0.35f else 1f
        if (active) {
            binding.etTextInput.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.etTextInput.windowToken, 0)
        } else {
            binding.inputVoiceBar.setLevel(0f)
        }

        val title = getString(if (active) R.string.voice_stop else R.string.voice_start)
        val icon = ContextCompat.getDrawable(
            this,
            if (active) R.drawable.ic_call_end_24 else R.drawable.ic_phone_24,
        )?.mutate()?.also {
            DrawableCompat.setTint(
                it,
                ContextCompat.getColor(this, if (active) R.color.brand_accent else R.color.brand_primary),
            )
        }
        binding.topAppBar.menu.findItem(R.id.action_voice_call)?.apply {
            this.icon = icon
            this.title = title
        }
    }

    private fun refreshConversations() {
        if (!::conversationAdapter.isInitialized) return
        val conversations = store.conversationSummaries()
        conversationAdapter.submitList(conversations)
        binding.topAppBar.title = conversations.firstOrNull { it.current }?.title.orEmpty()
        binding.topAppBar.subtitle = null
    }

    private fun selectConversation(conversation: ConversationSummary) {
        if (!conversation.current) VoiceAgentService.switchConversation(this, conversation.id)
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun showConversationMenu(anchor: android.view.View, conversation: ConversationSummary) {
        val popupContext = ContextThemeWrapper(this, R.style.ThemeOverlay_VoiceAssistant_PopupMenu)
        PopupMenu(popupContext, anchor).apply {
            menu.add(getString(R.string.conversation_rename)).setOnMenuItemClickListener {
                showRenameConversation(conversation)
                true
            }
            menu.add(getString(R.string.conversation_compact)).setOnMenuItemClickListener {
                VoiceAgentService.compactConversation(this@MainActivity, conversation.id)
                showMessage(getString(R.string.conversation_compact_started))
                true
            }
            menu.add(getString(R.string.conversation_delete)).setOnMenuItemClickListener {
                confirmDeleteConversation(conversation)
                true
            }
            show()
        }
    }

    private fun showRenameConversation(conversation: ConversationSummary) {
        val input = EditText(this).apply {
            setText(conversation.title)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            hint = getString(R.string.conversation_rename_hint)
        }
        val container = android.widget.FrameLayout(this).apply {
            val horizontal = (20 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, 0, horizontal, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conversation_rename)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotBlank()) VoiceAgentService.renameConversation(this, conversation.id, title)
            }
            .show()
    }

    private fun confirmDeleteConversation(conversation: ConversationSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conversation_delete_title)
            .setMessage(R.string.conversation_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.conversation_delete) { _, _ ->
                VoiceAgentService.deleteConversation(this, conversation.id)
            }
            .show()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private companion object {
        private const val MAX_ATTACHMENTS_PER_TURN = 6
    }
}
