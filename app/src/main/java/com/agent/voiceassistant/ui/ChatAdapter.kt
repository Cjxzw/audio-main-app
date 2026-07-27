package com.agent.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.agent.ReplyDetailPolicy
import com.agent.voiceassistant.ui.ChatRole.BOT
import com.agent.voiceassistant.ui.ChatRole.SYSTEM
import com.agent.voiceassistant.ui.ChatRole.USER
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import kotlin.math.roundToInt

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val messages = mutableListOf<ChatMessage>()
    private val maxMessages = 500
    private var markwon: Markwon? = null

    fun setMessages(items: List<ChatMessage>) {
        messages.clear()
        messages.addAll(items.takeLast(maxMessages))
        notifyDataSetChanged()
    }

    fun addMessage(msg: ChatMessage): Boolean {
        if (!msg.messageId.isNullOrBlank()) {
            val existing = messages.indexOfFirst { it.messageId == msg.messageId }
            if (existing >= 0) {
                messages[existing] = msg
                notifyItemChanged(existing, PAYLOAD_TEXT)
                return false
            }
        }
        if (!msg.toolCallId.isNullOrBlank()) {
            val existing = messages.indexOfFirst { it.toolCallId == msg.toolCallId }
            if (existing >= 0) {
                messages[existing] = msg
                notifyItemChanged(existing)
                return false
            }
        }
        messages.add(msg)
        if (messages.size > maxMessages) {
            messages.removeAt(0)
            notifyItemRemoved(0)
        }
        notifyItemInserted(messages.size - 1)
        return true
    }

    fun removeMessage(messageId: String) {
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index < 0) return
        messages.removeAt(index)
        notifyItemRemoved(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        val markdownRenderer = markwon ?: Markwon.builder(parent.context.applicationContext)
            .usePlugin(TablePlugin.create(parent.context.applicationContext))
            .build()
            .also { markwon = it }
        return VH(v, markdownRenderer)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(messages[position])

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TEXT)) {
            holder.bindStreamingText(messages[position])
        } else {
            holder.bind(messages[position])
        }
    }

    override fun getItemCount() = messages.size

    class VH(view: View, private val markwon: Markwon) : RecyclerView.ViewHolder(view) {
        private val llBubble = view.findViewById<LinearLayout>(R.id.llBubble)
        private val tvRole = view.findViewById<TextView>(R.id.tvRole)
        private val tvText = view.findViewById<TextView>(R.id.tvText)
        private val tvTime = view.findViewById<TextView>(R.id.tvTime)
        private val llToolStatus = view.findViewById<LinearLayout>(R.id.llToolStatus)
        private val tvToolSummary = view.findViewById<TextView>(R.id.tvToolSummary)
        private val tvToolState = view.findViewById<TextView>(R.id.tvToolState)

        fun bind(msg: ChatMessage) {
            val ctx = itemView.context
            val isToolStatus = !msg.toolCallId.isNullOrBlank()
            tvRole.visibility = if (isToolStatus) View.GONE else View.VISIBLE
            tvTime.visibility = if (isToolStatus) View.GONE else View.VISIBLE
            tvText.visibility = if (isToolStatus) View.GONE else View.VISIBLE
            llToolStatus.visibility = if (isToolStatus) View.VISIBLE else View.GONE
            if (isToolStatus) {
                llBubble.gravity = android.view.Gravity.START
                tvToolSummary.text = msg.text
                tvToolState.text = when (msg.toolStatus ?: inferToolStatus(msg.text)) {
                    ToolDisplayStatus.RUNNING -> "..."
                    ToolDisplayStatus.SUCCEEDED -> "✅"
                    ToolDisplayStatus.FAILED -> "❌"
                }
                return
            }
            tvText.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            tvText.textSize = 15f
            tvText.maxLines = Int.MAX_VALUE
            tvText.ellipsize = null
            val density = itemView.resources.displayMetrics.density
            val horizontal = (12 * density).roundToInt()
            val vertical = (10 * density).roundToInt()
            tvText.setPadding(horizontal, vertical, horizontal, vertical)
            tvText.maxWidth = (itemView.resources.displayMetrics.widthPixels * 0.82f).roundToInt()
            when (msg.role) {
                USER -> {
                    llBubble.gravity = android.view.Gravity.END
                    tvRole.text = ctx.getString(R.string.chat_role_user)
                    tvText.background = ContextCompat.getDrawable(ctx, R.drawable.bubble_user)
                    tvText.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
                }
                BOT -> {
                    llBubble.gravity = android.view.Gravity.START
                    tvRole.text = when (msg.streamState) {
                        ChatStreamState.STREAMING -> ctx.getString(R.string.chat_role_bot_streaming)
                        ChatStreamState.INTERRUPTED -> ctx.getString(R.string.chat_role_bot_interrupted)
                        else -> ctx.getString(R.string.chat_role_bot)
                    }
                    tvText.background = ContextCompat.getDrawable(
                        ctx,
                        if (msg.presentation == ChatPresentation.PERSONALIZED_VOICE) {
                            R.drawable.bubble_voice_reply
                        } else {
                            R.drawable.bubble_bot
                        },
                    )
                    tvText.setTextColor(ContextCompat.getColor(ctx, android.R.color.black))
                }
                SYSTEM -> {
                    llBubble.gravity = android.view.Gravity.CENTER
                    tvRole.text = ctx.getString(R.string.chat_role_system)
                    tvText.background = ContextCompat.getDrawable(ctx, R.drawable.bubble_system)
                    tvText.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
                }
            }
            bindText(msg)
            tvTime.text = msg.timeStr
        }

        fun bindStreamingText(msg: ChatMessage) {
            bindText(msg)
            tvRole.text = when (msg.streamState) {
                ChatStreamState.STREAMING -> itemView.context.getString(R.string.chat_role_bot_streaming)
                ChatStreamState.INTERRUPTED -> itemView.context.getString(R.string.chat_role_bot_interrupted)
                else -> itemView.context.getString(R.string.chat_role_bot)
            }
            tvTime.text = msg.timeStr
        }

        private fun bindText(msg: ChatMessage) {
            if (msg.role == BOT) {
                markwon.setMarkdown(tvText, ReplyDetailPolicy.forDisplay(msg.text))
            } else {
                tvText.text = msg.text
            }
        }

        private fun inferToolStatus(text: String): ToolDisplayStatus = when {
            text.trimEnd().endsWith("✅") -> ToolDisplayStatus.SUCCEEDED
            text.trimEnd().endsWith("❌") -> ToolDisplayStatus.FAILED
            else -> ToolDisplayStatus.RUNNING
        }
    }

    private companion object {
        const val PAYLOAD_TEXT = "chat_text"
    }
}
