package com.agent.voiceassistant.settings

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityInstrumentedTest {
    @Test
    fun settingsSubpagesAttachWithoutCrashing() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.supportFragmentManager.beginTransaction()
                    .replace(com.agent.voiceassistant.R.id.settingsContainer, LlmProvidersFragment())
                    .commitNowAllowingStateLoss()
                val fragment = activity.supportFragmentManager
                    .findFragmentById(com.agent.voiceassistant.R.id.settingsContainer) as? LlmProvidersFragment
                assertNotNull(fragment)
                assertFalse(fragment?.preferenceScreen?.preferenceCount == 0)

                activity.supportFragmentManager.beginTransaction()
                    .replace(com.agent.voiceassistant.R.id.settingsContainer, VoiceSettingsFragment())
                    .commitNowAllowingStateLoss()
                val voiceFragment = activity.supportFragmentManager
                    .findFragmentById(com.agent.voiceassistant.R.id.settingsContainer) as? VoiceSettingsFragment
                assertNotNull(voiceFragment)
                assertFalse(voiceFragment?.preferenceScreen?.preferenceCount == 0)
            }
        }
    }
}
