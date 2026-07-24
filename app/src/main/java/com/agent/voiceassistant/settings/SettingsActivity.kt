package com.agent.voiceassistant.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.agent.voiceassistant.R
import com.agent.voiceassistant.BuildConfig
import com.agent.voiceassistant.databinding.ActivitySettingsBinding
import com.agent.voiceassistant.workspace.WorkspaceActivity
import com.agent.voiceassistant.cloud.OpenAiCompatibleLlmClient
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.settingsToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        supportFragmentManager.addOnBackStackChangedListener { updateTitle() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, RootSettingsFragment())
                .commit()
        }
    }

    fun open(fragment: Fragment, title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, fragment)
            .addToBackStack(title)
            .commit()
        binding.settingsToolbar.title = title
    }

    private fun updateTitle() {
        binding.settingsToolbar.title = supportFragmentManager.getBackStackEntryAtOrNull(
            supportFragmentManager.backStackEntryCount - 1,
        )?.name ?: getString(R.string.settings_title)
    }

    private fun androidx.fragment.app.FragmentManager.getBackStackEntryAtOrNull(index: Int) =
        if (index in 0 until backStackEntryCount) getBackStackEntryAt(index) else null
}

class RootSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_mimo)
                summary = AppCapabilityResolver(requireContext()).capabilities().summary
                setIcon(R.drawable.ic_model_24)
                setOnPreferenceClickListener {
                    (activity as SettingsActivity).open(MimoSettingsFragment(), getString(R.string.settings_mimo))
                    true
                }
            })
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_custom_llm)
                summary = getString(R.string.settings_models_summary)
                setIcon(R.drawable.ic_model_24)
                setOnPreferenceClickListener {
                    (activity as SettingsActivity).open(LlmProvidersFragment(), getString(R.string.settings_models))
                    true
                }
            })
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_voice)
                summary = getString(R.string.settings_voice_summary)
                setIcon(R.drawable.ic_volume_24)
                setOnPreferenceClickListener {
                    (activity as SettingsActivity).open(VoiceSettingsFragment(), getString(R.string.settings_voice))
                    true
                }
            })
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_context_assets)
                summary = getString(R.string.settings_context_assets_summary)
                setIcon(R.drawable.ic_brain_24)
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), ContextAssetsActivity::class.java))
                    true
                }
            })
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_workspace)
                summary = getString(R.string.settings_workspace_summary)
                setIcon(R.drawable.ic_folder_24)
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), WorkspaceActivity::class.java))
                    true
                }
            })
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_about)
                summary = getString(R.string.settings_about_summary, BuildConfig.VERSION_NAME)
                setIcon(R.drawable.ic_info_24)
                setOnPreferenceClickListener {
                    (activity as SettingsActivity).open(AboutSettingsFragment(), getString(R.string.settings_about))
                    true
                }
            })
        }
    }
}

class AboutSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_about_app_name)
                summary = getString(R.string.settings_about_app_value)
                isSelectable = false
            })
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_about_version)
                summary = getString(
                    R.string.settings_about_version_value,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                )
                isSelectable = false
            })
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_about_github)
                summary = getString(R.string.settings_about_github_url)
                setIcon(R.drawable.ic_github_24)
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(getString(R.string.settings_about_github_url)),
                        ),
                    )
                    true
                }
            })
        }
    }
}

class MimoSettingsFragment : PreferenceFragmentCompat() {
    private lateinit var repository: MimoApiRepository
    private lateinit var keyPreference: EditTextPreference
    private lateinit var statusPreference: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        repository = MimoApiRepository(requireContext())
        rebuild()
    }

    private fun rebuild() {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            keyPreference = EditTextPreference(requireContext()).apply {
                key = "mimo_api_key_input"
                isPersistent = false
                title = getString(R.string.settings_mimo_key)
                dialogTitle = getString(R.string.settings_mimo_key)
                setOnBindEditTextListener { field ->
                    field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    field.setSingleLine(true)
                }
                setOnPreferenceChangeListener { _, newValue ->
                    val saved = runCatching { repository.saveKey(newValue.toString()) }
                        .onFailure { Toast.makeText(requireContext(), it.message, Toast.LENGTH_SHORT).show() }
                        .isSuccess
                    if (saved) refreshStatus()
                    false
                }
            }
            addPreference(keyPreference)

            statusPreference = Preference(requireContext()).apply {
                title = getString(R.string.settings_service_status)
                isSelectable = false
            }
            addPreference(statusPreference)

            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_mimo_test)
                summary = getString(R.string.settings_mimo_test_summary)
                setOnPreferenceClickListener {
                    testConnection()
                    true
                }
            })

            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_mimo_clear)
                isEnabled = repository.hasValidKey()
                setOnPreferenceClickListener {
                    repository.clearKey()
                    rebuild()
                    true
                }
            })
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        if (::statusPreference.isInitialized) {
            statusPreference.summary = AppCapabilityResolver(requireContext()).capabilities().summary
        }
        if (::keyPreference.isInitialized) {
            val type = repository.keyType()
            keyPreference.summary = if (type == null) {
                getString(R.string.settings_mimo_key_missing)
            } else {
                getString(R.string.settings_mimo_key_configured, type.label)
            }
        }
    }

    private fun testConnection() {
        if (!repository.hasValidKey()) {
            Toast.makeText(requireContext(), R.string.settings_mimo_key_missing, Toast.LENGTH_SHORT).show()
            return
        }
        statusPreference.summary = getString(R.string.settings_testing)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val client = OpenAiCompatibleLlmClient(repository.runtimeConfig())
                try {
                    client.testConnection()
                } finally {
                    client.close()
                }
            }
            statusPreference.summary = result.fold(
                onSuccess = { getString(R.string.settings_mimo_test_ok) },
                onFailure = { getString(R.string.settings_mimo_test_failed, it.message ?: "未知错误") },
            )
        }
    }
}

class LlmProvidersFragment : PreferenceFragmentCompat() {
    private lateinit var repository: LlmProviderRepository

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        repository = LlmProviderRepository(requireContext())
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) rebuild()
    }

    private fun rebuild() {
        val profiles = repository.profiles()
        val active = repository.activeProfile()
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        screen.addPreference(ListPreference(requireContext()).apply {
            key = "active_llm_provider"
            title = getString(R.string.settings_current_provider)
            entries = profiles.map { it.displayName }.toTypedArray()
            entryValues = profiles.map { it.id }.toTypedArray()
            value = active.id
            summary = active.displayName
            setOnPreferenceChangeListener { preference, newValue ->
                val selected = profiles.firstOrNull { it.id == newValue } ?: return@setOnPreferenceChangeListener false
                repository.setActive(selected.id)
                preference.summary = selected.displayName
                true
            }
        })

        val providerCategory = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.settings_manage_providers)
        }
        screen.addPreference(providerCategory)
        profiles.forEach { profile ->
            providerCategory.addPreference(Preference(requireContext()).apply {
                title = profile.displayName
                summary = "${profile.modelId} · ${profile.baseUrl}"
                isEnabled = !profile.builtIn
                if (!profile.builtIn) {
                    setOnPreferenceClickListener {
                        startActivity(
                            Intent(requireContext(), LlmProviderEditorActivity::class.java)
                                .putExtra(LlmProviderEditorActivity.EXTRA_PROFILE_ID, profile.id),
                        )
                        true
                    }
                }
            })
        }

        screen.addPreference(Preference(requireContext()).apply {
            title = getString(R.string.settings_add_provider)
            setIcon(R.drawable.ic_add_24)
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LlmProviderEditorActivity::class.java))
                true
            }
        })
    }
}

class VoiceSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val preferences = SpeechPreferences(requireContext())
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(SwitchPreferenceCompat(requireContext()).apply {
                key = "mute_text_replies"
                title = getString(R.string.settings_mute_text_replies)
                summary = getString(R.string.settings_mute_text_replies_summary)
                isChecked = preferences.muteTextReplies
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.muteTextReplies = newValue as Boolean
                    true
                }
            })
            addPreference(SeekBarPreference(requireContext()).apply {
                key = "tts_gain"
                title = getString(R.string.settings_tts_gain)
                min = SpeechPreferences.MIN_TTS_GAIN
                max = SpeechPreferences.MAX_TTS_GAIN
                seekBarIncrement = SpeechPreferences.STEP
                value = preferences.ttsGainPercent
                showSeekBarValue = true
                summary = getString(R.string.settings_tts_gain_summary, value)
                setOnPreferenceChangeListener { preference, newValue ->
                    val value = newValue as Int
                    preferences.ttsGainPercent = value
                    preference.summary = getString(R.string.settings_tts_gain_summary, preferences.ttsGainPercent)
                    true
                }
            })
        }
    }
}
