package com.agent.voiceassistant.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agent.voiceassistant.R
import com.agent.voiceassistant.agent.LlmProviderMode
import com.agent.voiceassistant.databinding.ActivityLlmProviderEditorBinding
import kotlinx.coroutines.launch

class LlmProviderEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLlmProviderEditorBinding
    private lateinit var repository: LlmProviderRepository
    private var profileId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLlmProviderEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = LlmProviderRepository(this)
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)

        binding.editorToolbar.setNavigationOnClickListener { finish() }
        binding.spinnerProviderMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("小米 MiMo 兼容", "通用 OpenAI Chat Completions"),
        )
        repository.profile(profileId)?.takeIf { !it.builtIn }?.let { profile ->
            binding.etProviderName.setText(profile.displayName)
            binding.etBaseUrl.setText(profile.baseUrl)
            binding.etModelId.setText(profile.modelId)
            binding.spinnerProviderMode.setSelection(if (profile.mode == LlmProviderMode.MIMO) 0 else 1)
            binding.checkProviderImages.isChecked = profile.supportsImages
            binding.btnDeleteProvider.visibility = View.VISIBLE
        }
        binding.etBaseUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.tvHttpWarning.visibility = if (s?.trim()?.startsWith("http://") == true) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.btnTestProvider.setOnClickListener { testConnection() }
        binding.btnSaveProvider.setOnClickListener { saveProvider() }
        binding.btnDeleteProvider.setOnClickListener { deleteProvider() }
    }

    private fun testConnection() {
        val profile = candidateProfile() ?: return
        setBusy(true)
        lifecycleScope.launch {
            runCatching { repository.test(profile, binding.etApiKey.text?.toString()) }
                .onSuccess { Toast.makeText(this@LlmProviderEditorActivity, getString(R.string.provider_test_ok, it.take(80)), Toast.LENGTH_LONG).show() }
                .onFailure { showError(it.message ?: "连接失败") }
            setBusy(false)
        }
    }

    private fun saveProvider() {
        val profile = candidateProfile() ?: return
        runCatching {
            repository.save(
                id = profileId,
                displayName = profile.displayName,
                baseUrl = profile.baseUrl,
                modelId = profile.modelId,
                mode = profile.mode,
                apiKey = binding.etApiKey.text?.toString(),
                supportsImages = binding.checkProviderImages.isChecked,
            )
        }.onSuccess {
            repository.setActive(it.id)
            Toast.makeText(this, R.string.provider_saved, Toast.LENGTH_SHORT).show()
            finish()
        }.onFailure { showError(it.message ?: "保存失败") }
    }

    private fun deleteProvider() {
        profileId?.let(repository::delete)
        Toast.makeText(this, R.string.provider_deleted, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun candidateProfile(): LlmProviderProfile? = runCatching {
        LlmProviderProfile(
            id = profileId ?: "candidate",
            displayName = binding.etProviderName.text?.toString()?.trim().orEmpty().also { require(it.isNotBlank()) { "请输入显示名称" } },
            baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty().also { require(it.isNotBlank()) { "请输入 Base URL" } },
            modelId = binding.etModelId.text?.toString()?.trim().orEmpty().also { require(it.isNotBlank()) { "请输入模型 ID" } },
            mode = if (binding.spinnerProviderMode.selectedItemPosition == 0) LlmProviderMode.MIMO else LlmProviderMode.OPENAI_COMPATIBLE,
            supportsImages = binding.checkProviderImages.isChecked,
        )
    }.onFailure { showError(it.message ?: "配置不完整") }.getOrNull()

    private fun setBusy(busy: Boolean) {
        binding.btnTestProvider.isEnabled = !busy
        binding.btnSaveProvider.isEnabled = !busy
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
