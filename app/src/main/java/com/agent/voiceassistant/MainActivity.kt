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
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.data.ConversationSummary
import com.agent.voiceassistant.databinding.ActivityMainBinding
import com.agent.voiceassistant.databinding.PageHomeBinding
import com.agent.voiceassistant.databinding.PageTasksBinding
import com.agent.voiceassistant.media.MainMediaLibraryService
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.service.ServiceState
import com.agent.voiceassistant.service.VoiceAgentService
import com.agent.voiceassistant.settings.SettingsActivity
import com.agent.voiceassistant.ui.ChatAdapter
import com.agent.voiceassistant.ui.ConversationAdapter
import com.agent.voiceassistant.ui.MainPage
import com.agent.voiceassistant.ui.MainPageAdapter
import com.agent.voiceassistant.ui.TaskAdapter
import com.agent.voiceassistant.tasks.TaskEntity
import com.agent.voiceassistant.tasks.TaskRepository
import com.agent.voiceassistant.workspace.WorkspaceRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeBinding: PageHomeBinding
    private lateinit var tasksBinding: PageTasksBinding
    private lateinit var pageTabsMediator: TabLayoutMediator
    private lateinit var store: ConversationStore
    private val chatAdapter = ChatAdapter()
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var workspace: WorkspaceRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var taskAdapter: TaskAdapter
    private val pendingAttachments = mutableListOf<WorkspaceRepository.Entry>()
    private var pendingCameraFile: File? = null
    private var agentListening = false
    private var pendingLegacyShareUris: List<Uri> = emptyList()
    private var chatTailFollowPending = false
    private var chatTailFollowEnabled = true

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
        taskRepository = TaskRepository(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        homeBinding = PageHomeBinding.inflate(layoutInflater)
        tasksBinding = PageTasksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val mainPages = listOf(
            MainPage(R.string.page_home, homeBinding.root),
            MainPage(R.string.page_tasks, tasksBinding.root),
        )
        val pageAdapter = MainPageAdapter(mainPages)
        binding.pagePager.adapter = pageAdapter
        pageTabsMediator = TabLayoutMediator(binding.pageTabs, binding.pagePager) { tab, position ->
            tab.setText(pageAdapter.pages[position].titleRes)
        }.also(TabLayoutMediator::attach)
        MainMediaLibraryService.ensureStarted(this)
        ensureNotificationPermissionAndBootstrap()

        homeBinding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            // A streamed message changes many times per second; per-item animations make it jump.
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    when (newState) {
                        RecyclerView.SCROLL_STATE_DRAGGING -> chatTailFollowEnabled = false
                        RecyclerView.SCROLL_STATE_IDLE -> if (!chatTailFollowPending) {
                            chatTailFollowEnabled = !recyclerView.canScrollVertically(1)
                        }
                    }
                }
            })
        }
        taskAdapter = TaskAdapter(
            onOpen = ::showTaskDetails,
            onCancel = { VoiceAgentService.cancelTask(this, it.taskId) },
        )
        tasksBinding.rvTasks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = taskAdapter
        }
        lifecycleScope.launch {
            taskRepository.observeAll().collectLatest { tasks ->
                taskAdapter.submitList(tasks)
                tasksBinding.tvTaskEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
            }
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
                else -> false
            }
        }

        homeBinding.btnAttach.setOnClickListener { showAttachmentMenu() }
        binding.btnNewConversation.setOnClickListener {
            showMessage(getString(R.string.conversation_new_preparing))
            VoiceAgentService.newConversation(this)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.btnSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        TooltipCompat.setTooltipText(homeBinding.btnAttach, getString(R.string.attachment_add))
        TooltipCompat.setTooltipText(homeBinding.btnSendText, getString(R.string.btn_send_text))
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

        homeBinding.btnSendText.setOnClickListener {
            sendTextInput()
        }
        homeBinding.etTextInput.setOnEditorActionListener { _, actionId, _ ->
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
            homeBinding.rvChat.post { homeBinding.rvChat.scrollToPosition(chatAdapter.itemCount - 1) }
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
        val text = homeBinding.etTextInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank() && pendingAttachments.isEmpty()) return
        val attachments = pendingAttachments.map(WorkspaceRepository.Entry::virtualPath)
        val effectiveText = text.ifBlank { "请查看这些附件。" }
        homeBinding.etTextInput.setText("")
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
        homeBinding.tvPendingAttachments.visibility = if (pendingAttachments.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        homeBinding.tvPendingAttachments.text = getString(
            R.string.attachment_pending,
            pendingAttachments.joinToString { it.name },
        )
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(VoiceAgentService.EXTRA_OPEN_TASKS, false) == true) {
            binding.pagePager.setCurrentItem(1, false)
            intent.removeExtra(VoiceAgentService.EXTRA_OPEN_TASKS)
        }
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
                val inserted = chatAdapter.addMessage(msg)
                if (chatTailFollowEnabled) scheduleChatTailFollow()
                if (inserted) refreshConversations()
                Timber.d("UI chat: [${msg.role}] ${msg.text}")
            }
        }
        lifecycleScope.launch {
            EventBus.chatRemovals.collectLatest { messageId ->
                chatAdapter.removeMessage(messageId)
            }
        }
        lifecycleScope.launch {
            EventBus.chatResets.collectLatest { messages ->
                chatAdapter.setMessages(messages)
                if (messages.isNotEmpty()) {
                    homeBinding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
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
            EventBus.conversationBusy.collectLatest { busy ->
                binding.btnNewConversation.isEnabled = !busy
            }
        }
        lifecycleScope.launch {
            EventBus.userNotices.collectLatest(::showMessage)
        }
        lifecycleScope.launch {
            EventBus.volumeEvents.collectLatest { level ->
                homeBinding.inputVoiceBar.setLevel(level)
            }
        }
    }

    private fun scheduleChatTailFollow() {
        if (chatTailFollowPending || chatAdapter.itemCount == 0) return
        chatTailFollowPending = true
        homeBinding.rvChat.doOnNextLayout {
            chatTailFollowPending = false
            if (!chatTailFollowEnabled) return@doOnNextLayout
            val recyclerView = homeBinding.rvChat
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return@doOnNextLayout
            val lastPosition = chatAdapter.itemCount - 1
            val lastView = layoutManager.findViewByPosition(lastPosition)
            if (lastView == null) {
                recyclerView.scrollToPosition(lastPosition)
                return@doOnNextLayout
            }
            val overflow = lastView.bottom - (recyclerView.height - recyclerView.paddingBottom)
            if (overflow > 0) recyclerView.scrollBy(0, overflow)
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
        homeBinding.etTextInput.isEnabled = !active
        homeBinding.etTextInput.visibility = if (active) View.GONE else View.VISIBLE
        homeBinding.inputVoiceBar.visibility = if (active) View.VISIBLE else View.GONE
        homeBinding.btnAttach.isEnabled = !active
        homeBinding.btnSendText.isEnabled = !active
        homeBinding.btnAttach.alpha = if (active) 0.35f else 1f
        homeBinding.btnSendText.alpha = if (active) 0.35f else 1f
        if (active) {
            homeBinding.etTextInput.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(homeBinding.etTextInput.windowToken, 0)
        } else {
            homeBinding.inputVoiceBar.setLevel(0f)
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

    private fun showTaskDetails(task: TaskEntity) {
        val text = buildString {
            appendLine("任务 ID：${task.taskId}")
            appendLine("执行者：${task.executorName}（${task.executorId}）")
            appendLine("状态：${task.status} · 进度 ${task.progress}%")
            appendLine("优先级：${task.priority}")
            appendLine("汇报：${task.reportState}")
            if (task.summary.isNotBlank()) appendLine("结果：${task.summary}")
            if (task.error.isNotBlank()) appendLine("错误：${task.error}")
            if (task.outputPath.isNotBlank()) append("产物：${task.outputPath}")
        }.trim()
        MaterialAlertDialogBuilder(this)
            .setTitle(task.title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroy() {
        pageTabsMediator.detach()
        super.onDestroy()
    }

    private companion object {
        private const val MAX_ATTACHMENTS_PER_TURN = 6
    }
}
