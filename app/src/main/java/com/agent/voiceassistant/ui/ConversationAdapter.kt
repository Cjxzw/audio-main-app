package com.agent.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.data.ConversationSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val onSelect: (ConversationSummary) -> Unit,
    private val onMore: (View, ConversationSummary) -> Unit,
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {
    private val items = mutableListOf<ConversationSummary>()

    fun submitList(conversations: List<ConversationSummary>) {
        items.clear()
        items.addAll(conversations)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(items[position], onSelect, onMore)

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.tvConversationTitle)
        private val preview = view.findViewById<TextView>(R.id.tvConversationPreview)
        private val time = view.findViewById<TextView>(R.id.tvConversationTime)
        private val more = view.findViewById<ImageButton>(R.id.btnConversationMore)
        private val dateFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

        fun bind(
            item: ConversationSummary,
            onSelect: (ConversationSummary) -> Unit,
            onMore: (View, ConversationSummary) -> Unit,
        ) {
            val context = itemView.context
            title.text = item.title
            preview.text = item.preview.ifBlank { context.getString(R.string.conversation_empty_preview) }
            time.text = context.getString(
                R.string.conversation_message_count,
                item.messageCount,
                dateFormat.format(Date(item.updatedAt)),
            )
            itemView.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (item.current) R.color.surface_selected else android.R.color.transparent,
                ),
            )
            itemView.setOnClickListener { onSelect(item) }
            more.setOnClickListener { onMore(more, item) }
        }
    }
}
