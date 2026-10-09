package com.agent.voiceassistant

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.data.ConversationDomain
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.service.RealtimeState
import com.agent.voiceassistant.service.VoiceAgentService
import com.agent.voiceassistant.service.ServiceState
import com.agent.voiceassistant.ui.ChatAdapter
import com.agent.voiceassistant.ui.VoiceBarView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Dedicated transcript surface for the full-duplex Realtime session. */
class RealtimeActivity : AppCompatActivity() {
    private lateinit var store: ConversationStore
    private lateinit var adapter: ChatAdapter
    private var conversationId: String? = null
    private var sessionObserved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realtime)
        store = ConversationStore(this)
        adapter = ChatAdapter()
        val list = findViewById<RecyclerView>(R.id.rvRealtimeChat)
        val voiceBar = findViewById<VoiceBarView>(R.id.realtimeVoiceBar)
        val textInput = findViewById<EditText>(R.id.etRealtimeTextInput)
        val sendButton = findViewById<View>(R.id.btnRealtimeSendText)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.itemAnimator = null
        list.adapter = adapter
        fun sendText() {
            val text = textInput.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return
            textInput.setText("")
            VoiceAgentService.sendText(this, text)
        }
        sendButton.setOnClickListener { sendText() }
        textInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.isShiftPressed.not())
            ) {
                sendText()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.btnRealtimeClose).setOnClickListener {
            VoiceAgentService.stopRealtime(this)
            finish()
        }
        bindConversation()
        lifecycleScope.launch {
            EventBus.chatMessages.collectLatest { message ->
                if (message.conversationId == conversationId) {
                    adapter.addMessage(message)
                    list.post { if (adapter.itemCount > 0) list.scrollToPosition(adapter.itemCount - 1) }
                } else if (conversationId == null) {
                    bindConversation()
                }
            }
        }
        lifecycleScope.launch {
            EventBus.chatRemovals.collectLatest { id -> adapter.removeMessage(id) }
        }
        lifecycleScope.launch {
            EventBus.volumeEvents.collectLatest { level -> voiceBar.setLevel(level) }
        }
        lifecycleScope.launch {
            EventBus.realtimeStates.collectLatest { state ->
                findViewById<android.widget.TextView>(R.id.tvRealtimeStatus).text = when (state) {
                    RealtimeState.CONNECTING -> "正在连接 Realtime…"
                    RealtimeState.READY -> "StepFun Realtime · 已连接"
                    RealtimeState.FAILED -> "Realtime 连接失败"
                    RealtimeState.STOPPED -> "Realtime 已挂断"
                }
            }
        }
        lifecycleScope.launch {
            EventBus.states.collectLatest { state ->
                if (state == ServiceState.LISTENING) sessionObserved = true
                if (state == ServiceState.LISTENING) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else if (state in setOf(ServiceState.IDLE, ServiceState.DORMANT, ServiceState.FAILED)) {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                if (sessionObserved && state in setOf(ServiceState.IDLE, ServiceState.DORMANT, ServiceState.FAILED)) {
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bindConversation()
    }

    override fun onDestroy() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun bindConversation() {
        val id = conversationId ?: store.latestConversationId(ConversationDomain.REALTIME) ?: return
        conversationId = id
        adapter.setMessages(store.recentChatMessagesForConversation(id))
        findViewById<RecyclerView>(R.id.rvRealtimeChat).post {
            if (adapter.itemCount > 0) findViewById<RecyclerView>(R.id.rvRealtimeChat).scrollToPosition(adapter.itemCount - 1)
        }
    }
}
