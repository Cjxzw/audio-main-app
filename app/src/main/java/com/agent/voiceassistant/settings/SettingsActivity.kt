package com.agent.voiceassistant.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.agent.voiceassistant.R
import com.agent.voiceassistant.databinding.ActivitySettingsBinding
import com.agent.voiceassistant.workspace.WorkspaceActivity

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
                title = getString(R.string.settings_models)
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
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), ContextAssetsActivity::class.java))
                    true
                }
            })
            addPreference(Preference(requireContext()).apply {
                title = getString(R.string.settings_workspace)
                summary = getString(R.string.settings_workspace_summary)
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), WorkspaceActivity::class.java))
                    true
                }
            })
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
