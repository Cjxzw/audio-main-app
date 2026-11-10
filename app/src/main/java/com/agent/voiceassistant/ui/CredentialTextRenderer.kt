package com.agent.voiceassistant.ui

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.BackgroundColorSpan
import android.view.View
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.settings.CredentialEditorActivity
import com.agent.voiceassistant.tools.CredentialPolicy
import com.agent.voiceassistant.tools.CredentialVault

object CredentialTextRenderer {
    fun render(context: Context, message: ChatMessage): CharSequence {
        if (message.role != ChatRole.USER || message.credentialReferences.isEmpty()) return message.text
        val vault = CredentialVault(context)
        val active by lazy { vault.records().associateBy { it.id } }
        val restored = message.conversationId?.let { ConversationStore(context).restoredCredentialValues(it) }.orEmpty()
        val result = SpannableStringBuilder(message.text)
        CredentialPolicy.reference.findAll(message.text).toList().asReversed().forEach { match ->
            if (match.value !in message.credentialReferences) return@forEach
            val plain = restored[match.value]
            val credential = if (plain == null) active[match.groupValues[1]]?.let { record ->
                record.fields.firstOrNull { it.id == match.groupValues[2] }?.let { record to it }
            } else null
            val value = plain ?: credential?.second?.value ?: return@forEach
            val start = match.range.first
            result.replace(start, match.range.last + 1, value)
            if (credential != null) {
                val end = start + value.length
                result.setSpan(BackgroundColorSpan(Color.argb(45, 55, 130, 190)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        context.startActivity(CredentialEditorActivity.intent(context, credential.first.id, match.value, message.conversationId))
                    }
                    override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true; ds.color = ds.linkColor }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return result
    }
}
