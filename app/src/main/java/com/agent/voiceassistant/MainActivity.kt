package com.agent.voiceassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.databinding.ActivityMainBinding
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.service.ServiceState
import com.agent.voiceassistant.service.VoiceAgentService
import com.agent.voiceassistant.ui.ChatAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ConversationStore
    private val chatAdapter = ChatAdapter()
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConversationStore(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            itemAnimator = DefaultItemAnimator().apply { addDuration = 150 }
        }
        loadPersistedChat()

        binding.btnToggle.setOnClickListener {
            val isListening = binding.btnToggle.text == getString(R.string.btn_stop)
            if (isListening) {
                VoiceAgentService.stop(this)
                binding.btnToggle.text = getString(R.string.btn_start)
                binding.tvStatus.text = getString(R.string.status_idle)
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
        if (text.isBlank()) return
        binding.etTextInput.setText("")
        VoiceAgentService.sendText(this, text)
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
            EventBus.pendingCounts.collectLatest { count ->
                binding.tvPendingCount.text = "待汇报: $count"
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
                Timber.v("Volume UI: ${"%.4f".format(level)}")
            }
        }
    }

    private fun updateStateDisplay(state: ServiceState) {
        val text = when (state) {
            ServiceState.IDLE -> getString(R.string.status_idle)
            ServiceState.DORMANT -> getString(R.string.status_dormant)
            ServiceState.INITIALIZING -> getString(R.string.status_initializing)
            ServiceState.READY -> "已就绪"
            ServiceState.LISTENING -> getString(R.string.status_listening)
            ServiceState.FAILED -> getString(R.string.status_failed)
        }
        binding.tvStatus.text = text
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
    }
}
