package com.agent.voiceassistant

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.data.ConversationDomain
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.service.VoiceAgentService
import com.agent.voiceassistant.ui.ChatAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Dedicated transcript surface for the full-duplex Realtime session. */
class RealtimeActivity : AppCompatActivity() {
    private lateinit var store: ConversationStore
    private lateinit var adapter: ChatAdapter
    private var conversationId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realtime)
        store = ConversationStore(this)
        adapter = ChatAdapter()
        val list = findViewById<RecyclerView>(R.id.rvRealtimeChat)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.itemAnimator = null
        list.adapter = adapter
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
    }

    override fun onResume() {
        super.onResume()
        bindConversation()
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
