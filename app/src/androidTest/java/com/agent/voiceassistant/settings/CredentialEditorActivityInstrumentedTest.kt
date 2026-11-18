package com.agent.voiceassistant.settings

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.tools.CredentialVault
import com.agent.voiceassistant.tools.VaultCredential
import com.agent.voiceassistant.tools.VaultField
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CredentialEditorActivityInstrumentedTest {
    @Test
    fun createAndEditCredentialScreensLaunchWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context)
        val record = VaultCredential(
            name = "editor-test-${UUID.randomUUID()}",
            sessionId = "instrumentation",
            automatic = true,
            createdAt = System.currentTimeMillis(),
            fields = listOf(VaultField(name = "token", type = "key", value = "dummy-test-value")),
        )
        vault.save(record)
        try {
            ActivityScenario.launch<CredentialEditorActivity>(CredentialEditorActivity.intent(context)).use { scenario ->
                scenario.onActivity { activity ->
                    val toolbar = allViewsWithText(activity, MaterialToolbar::class.java).single()
                    assertEquals("新增凭证", toolbar.title)
                    assertTrue(allTextInputLayouts(activity).size >= 4)
                }
            }
            ActivityScenario.launch<CredentialEditorActivity>(CredentialEditorActivity.intent(context, record.id)).use { scenario ->
                scenario.onActivity { activity ->
                    val toolbar = allViewsWithText(activity, MaterialToolbar::class.java).single()
                    assertEquals("编辑凭证", toolbar.title)
                    assertTrue(allTextInputLayouts(activity).size >= 4)
                }
            }
        } finally {
            vault.delete(record.id)
        }
    }

    private fun allTextInputLayouts(activity: CredentialEditorActivity): List<TextInputLayout> =
        allViewsWithText(activity, TextInputLayout::class.java)

    private fun <T : android.view.View> allViewsWithText(activity: CredentialEditorActivity, type: Class<T>): List<T> {
        val found = mutableListOf<T>()
        fun visit(view: android.view.View) {
            if (type.isInstance(view)) type.cast(view)?.let(found::add)
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(activity.window.decorView)
        return found
    }
}
