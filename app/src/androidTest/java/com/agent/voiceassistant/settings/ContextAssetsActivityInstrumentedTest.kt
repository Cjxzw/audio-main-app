package com.agent.voiceassistant.settings

import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.voiceassistant.R
import com.agent.voiceassistant.data.ConversationStore
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContextAssetsActivityInstrumentedTest {
    @Test
    fun separatePagesSearchClearAndRestoreWithoutChangingMemories() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ConversationStore(context)
        val before = store.memories()
        val titles = listOf(R.string.context_assets_skills, R.string.context_assets_memories, R.string.context_assets_rules)
        ContextAssetsActivity.Tab.values().forEachIndexed { index, tab ->
            ActivityScenario.launch<ContextAssetsActivity>(ContextAssetsActivity.intent(context, tab)).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(context.getString(titles[index]), activity.findViewById<MaterialToolbar>(R.id.contextAssetsToolbar).title)
                    val list = activity.findViewById<RecyclerView>(R.id.contextAssetsList)
                    val count = list.adapter!!.itemCount
                    val field = activity.findViewById<EditText>(R.id.contextAssetsSearch)
                    field.setText("__hanwo_nonmatching_query_983752__")
                    assertEquals(0, list.adapter!!.itemCount)
                    assertEquals(context.getString(R.string.context_assets_no_matches), activity.findViewById<TextView>(R.id.contextAssetsEmpty).text)
                    field.setText("")
                    assertEquals(count, list.adapter!!.itemCount)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(context.getString(titles[index]), activity.findViewById<MaterialToolbar>(R.id.contextAssetsToolbar).title)
                }
            }
        }
        assertEquals(before, store.memories())
    }
}
