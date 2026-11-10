package com.agent.voiceassistant.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.agent.voiceassistant.R
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.service.EventBus
import com.agent.voiceassistant.tools.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import java.text.DateFormat
import java.util.Date

class CredentialEditorActivity : AppCompatActivity() {
    private lateinit var vault: CredentialVault
    private lateinit var original: VaultCredential
    private lateinit var name: EditText
    private lateinit var urls: EditText
    private lateinit var permanent: SwitchMaterial
    private lateinit var fieldsContainer: LinearLayout
    private val rows = mutableListOf<FieldRow>()
    private var creating = false
    private data class FieldRow(val original: VaultField?, val name: EditText, val value: EditText)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = CredentialVault(this)
        val id = intent.getStringExtra(EXTRA_ID)
        creating = id == null
        original = if (id == null) VaultCredential(name = "", sessionId = "", automatic = false,
            createdAt = System.currentTimeMillis(), fields = emptyList())
        else vault.get(id) ?: run { Toast.makeText(this, "凭证已删除或到期", Toast.LENGTH_SHORT).show(); finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = if (creating) "新增凭证" else "编辑凭证"
            setNavigationIcon(R.drawable.ic_arrow_back_24)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_text_editor)
            menu.findItem(R.id.action_delete_memory).isVisible = !creating
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_save_text -> { save(); true }
                    R.id.action_delete_memory -> { confirmDelete(); true }
                    else -> false
                }
            }
        }
        root.addView(toolbar)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        name = input(form, "凭证名称", original.name)
        urls = input(form, "允许的 URL / 域名，每行一项；空白或 all 表示全部", original.allowedUrls.joinToString("\n"), multiline = true)
        form.addView(TextView(this).apply {
            text = "支持完整 URL 前缀、域名及 *.example.com 等主机通配符。引用使用稳定 UID，改名不会影响旧引用。修改值或移除键后，历史引用保留旧值至凭证到期；删除整条凭证可一并作废。"
            setPadding(0, dp(8), 0, dp(8))
        })
        permanent = SwitchMaterial(this).apply { text = "永久保存"; isChecked = original.expiresAt == null }
        form.addView(permanent)
        val expiration = TextView(this).apply { text = original.expiresAt?.let { "临时凭证到期：${DateFormat.getDateTimeInstance().format(Date(it))}" } ?: "当前永久保存；关闭后从本次保存起保留 30 天" }
        form.addView(expiration)
        fieldsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(fieldsContainer)
        original.fields.filterNot { it.archived }.forEach(::addField)
        if (rows.isEmpty()) addField(null)
        form.addView(Button(this).apply { text = "添加键值"; setOnClickListener { addField(null) } })
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val session = intent.getStringExtra(EXTRA_SESSION)
        if (!token.isNullOrBlank() && !session.isNullOrBlank()) {
            form.addView(Button(this).apply {
                text = "恢复明文显示"
                setOnClickListener {
                    runCatching {
                        val (_, field) = vault.lookup(token) ?: error("凭证已删除或到期")
                        val conversations = ConversationStore(this@CredentialEditorActivity)
                        check(conversations.restoreCredential(session, token, field.value)) { "原会话已不存在" }
                        // Other conversations may still depend on a reused credential.
                        if (!conversations.hasCredentialReferencesOutsideConversation(token, session)) vault.removeAutomaticField(token, session)
                        EventBus.emitConversationUpdate()
                        Toast.makeText(this@CredentialEditorActivity, "已恢复；真值说明将在本会话下一次模型请求时附带发送", Toast.LENGTH_LONG).show()
                        finish()
                    }.onFailure { showError(it) }
                }
            })
        }
        root.addView(ScrollView(this).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun input(parent: LinearLayout, label: String, value: String, multiline: Boolean = false, secret: Boolean = false): EditText {
        val layout = TextInputLayout(this).apply { hint = label }
        val field = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0) or (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
            setSingleLine(!multiline)
            setText(value)
            if (secret) { setSelectAllOnFocus(true); isSaveEnabled = false }
        }
        if (secret) layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        layout.addView(field, ViewGroup.LayoutParams(-1, -2))
        parent.addView(layout, LinearLayout.LayoutParams(-1, -2))
        return field
    }

    private fun addField(field: VaultField?) {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
        val key = input(group, "键名", field?.name.orEmpty())
        val value = input(group, "值", field?.value.orEmpty(), multiline = true, secret = true)
        val row = FieldRow(field, key, value)
        rows += row
        field?.let { group.addView(TextView(this).apply {
            text = CredentialPolicy.token(original, it); setTextIsSelectable(true); textSize = 12f
        }) }
        group.addView(Button(this).apply { text = "移除此键"; setOnClickListener { rows.remove(row); fieldsContainer.removeView(group) } })
        fieldsContainer.addView(group)
    }

    private fun save() {
        runCatching {
            val active = rows.map { row ->
                val key = row.name.text.toString().trim()
                val value = row.value.text.toString()
                val old = row.original
                if (old != null && old.value == value) old.copy(name = key)
                else VaultField(name = key, type = old?.type ?: "key", value = value)
            }
            require(active.map { it.name }.distinct().size == active.size) { "键名不能重复" }
            // Value changes get a new UID. Existing references retain the previous value until expiry/deletion.
            val ids = active.map { it.id }.toSet()
            val historical = original.fields.filterNot { it.id in ids }.map { it.copy(archived = true) }
            if (!creating) check(vault.get(original.id) != null) { "凭证已到期或删除" }
            vault.save(original.copy(name = name.text.toString().trim(), automatic = false,
                fields = active + historical, allowedUrls = urls.text.toString().lineSequence().map(String::trim).filter(String::isNotBlank).toList().ifEmpty { listOf("all") },
                expiresAt = if (permanent.isChecked) null else original.expiresAt ?: (System.currentTimeMillis() + CredentialPolicy.TTL_MS)))
            EventBus.emitConversationUpdate()
            finish()
        }.onFailure(::showError)
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this).setTitle("删除凭证？")
            .setMessage("删除后相关引用无法使用，聊天中的明文回显会退回为引用代码。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("删除") { _, _ -> vault.delete(original.id); EventBus.emitConversationUpdate(); finish() }.show()
    }
    private fun showError(error: Throwable) { Toast.makeText(this, error.message ?: "凭证操作失败", Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "credential_id"
        private const val EXTRA_TOKEN = "credential_token"
        private const val EXTRA_SESSION = "credential_session"
        fun intent(context: Context, id: String? = null, token: String? = null, sessionId: String? = null) =
            Intent(context, CredentialEditorActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_SESSION, sessionId)
    }
}
