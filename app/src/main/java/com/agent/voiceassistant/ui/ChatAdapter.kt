package com.agent.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.ui.ChatRole.BOT
import com.agent.voiceassistant.ui.ChatRole.SYSTEM
import com.agent.voiceassistant.ui.ChatRole.USER
import timber.log.Timber

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val messages = mutableListOf<ChatMessage>()
    private val maxMessages = 100

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        if (messages.size > maxMessages) {
            messages.removeAt(0)
            notifyItemRemoved(0)
        }
        notifyItemInserted(messages.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(messages[position])

    override fun getItemCount() = messages.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val llBubble = view.findViewById<LinearLayout>(R.id.llBubble)
        private val tvRole = view.findViewById<TextView>(R.id.tvRole)
        private val tvText = view.findViewById<TextView>(R.id.tvText)
        private val tvTime = view.findViewById<TextView>(R.id.tvTime)

        fun bind(msg: ChatMessage) {
            val ctx = itemView.context
            when (msg.role) {
                USER -> {
                    llBubble.gravity = android.view.Gravity.END
                    tvRole.text = ctx.getString(R.string.chat_role_user)
                    tvText.background = ContextCompat.getDrawable(ctx, R.drawable.bubble_user)
                    tvText.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
                }
                BOT -> {
                    llBubble.gravity = android.view.Gravity.START
                    tvRole.text = ctx.getString(R.string.chat_role_bot)
                    tvText.background = ContextCompat.getDrawable(ctx, R.drawable.bubble_bot)
                    tvText.setTextColor(ContextCompat.getColor(ctx, android.R.color.black))
                }
                SYSTEM -> {
                    llBubble.gravity = android.view.Gravity.CENTER
                    tvRole.text = ctx.getString(R.string.chat_role_system)
                    tvText.background = ContextCompat.getDrawable(ctx, R.drawable.bubble_system)
                    tvText.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
                }
            }
            tvText.text = msg.text
            tvTime.text = msg.timeStr
        }
    }
}
