package com.agent.voiceassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.agent.voiceassistant.databinding.ActivityMainBinding
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.service.ServiceState
import com.agent.voiceassistant.service.VoiceAgentService
import com.agent.voiceassistant.ui.ChatAdapter
import com.agent.voiceassistant.ui.ChatRole
import com.agent.voiceassistant.ui.ChatMessage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val chatAdapter = ChatAdapter()
    private val logBuilder = StringBuilder()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
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
        val allGranted = result.values.all { it }
        if (allGranted) {
            Timber.i("Permissions granted")
            startAgentService()
        } else {
            appendLog("权限被拒绝，无法启动")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            itemAnimator = DefaultItemAnimator().apply { addDuration = 150 }
        }

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
            logBuilder.clear()
            binding.tvLog.text = ""
        }

        observeEventBus()
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
        logBuilder.append("[$time] $msg\n")
        if (logBuilder.length > 8192) {
            logBuilder.delete(0, logBuilder.length - 8192)
        }
        binding.tvLog.text = logBuilder.toString()
        binding.svLog.post { binding.svLog.fullScroll(android.view.View.FOCUS_DOWN) }
        Timber.d("UI_LOG: $msg")
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
