package com.agent.voiceassistant.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.cloud.CloudSpeechClient.ResponseMetadata
import com.agent.voiceassistant.settings.ConversationLifecycleConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolated stores; never creates or deletes conversations in the user's store. */
@RunWith(AndroidJUnit4::class)
class ColdConversationInstrumentedTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(base) {
            private val directory = File(base.cacheDir, "cold-test-${UUID.randomUUID()}").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
    }

    private fun seed(context: Context, sessions: List<ConversationSession>): ConversationStore {
        File(context.filesDir, "main-agent-store.json").writeText(json.encodeToString(
            StoreState(currentConversationId = sessions.first().id, sessions = sessions.toMutableList()),
        ))
        return ConversationStore(context)
    }

    private fun persisted(context: Context): StoreState = json.decodeFromString(
        File(context.filesDir, "main-agent-store.json").readText(),
    )

    @Test fun streamingReplyUsageIsPersistedOnAddAndUpdate() {
        val context = isolatedContext()
        val store = ConversationStore(context)
        val reply = store.addMessage("assistant", "流式正文", responseMetadata = ResponseMetadata(promptTokens = 110_000L))
        assertEquals(110_000L, store.currentConversationContextUsage())
        // A smaller later prompt must replace the previous value, rather than retaining the peak.
        store.updateMessage(reply.id, "完整正文", responseMetadata = ResponseMetadata(promptTokens = 101_000L))
        store.updateMessage(reply.id, "完成后的展示整理")
        store.addMessage("assistant", "本地提示", responseMetadata = ResponseMetadata(promptTokens = 0L))
        assertEquals(101_000L, store.currentConversationSummary().contextUsageTokens)
        assertEquals(101_000L, persisted(context).sessions.single().contextUsageTokens)
    }

    @Test fun legacyUsageBackfillPreservesInactivityAndConversationHistory() {
        val context = isolatedContext()
        val now = System.currentTimeMillis()
        val old = ConversationSession(
            id = "legacy", createdAt = now - 900_000L, updatedAt = now - 600_000L,
            messages = mutableListOf(
                StoredMessage(id = "user", role = "user", content = "保留的问题", timestamp = now - 700_000L),
                StoredMessage(id = "reply1", role = "assistant", content = "较早回答", timestamp = now - 650_000L, promptTokens = 150_000L),
                StoredMessage(id = "reply2", role = "assistant", content = "最后回答", timestamp = now - 600_000L, promptTokens = 120_000L),
            ),
        )
        val known = old.copy(id = "explicit-usage", contextUsageTokens = 71_000L)
        val store = seed(context, listOf(old, known))
        assertEquals(120_000L, store.currentConversationContextUsage())
        assertEquals(old.updatedAt, store.currentConversationSummary().updatedAt)
        assertEquals(71_000L, store.conversationSummaries().single { it.id == known.id }.contextUsageTokens)
        assertEquals(120_000L, persisted(context).sessions.first().contextUsageTokens)

        val created = store.startNewConversationIfCold(ConversationLifecycleConfig(), now)
        assertNotNull(created)
        assertNotEquals(old.id, store.currentConversationId)
        assertTrue(store.recentChatMessages().isEmpty())
        assertEquals(old.messages.map { it.content }, store.recentChatMessagesForConversation(old.id).map { it.text })
        assertEquals(old.updatedAt, store.conversationSummaries().single { it.id == old.id }.updatedAt)
        assertNull(store.startNewConversationIfCold(ConversationLifecycleConfig(), now + 60_000L))
    }

    @Test fun bothThresholdsAndStandardDomainAreRequired() {
        val now = System.currentTimeMillis()
        data class Case(
            val tokens: Long?, val inactiveMs: Long, val rotate: Boolean,
            val config: ConversationLifecycleConfig = ConversationLifecycleConfig(),
            val domain: ConversationDomain = ConversationDomain.STANDARD,
        )
        listOf(
            Case(null, 6_000_000L, false),
            Case(99_999L, 600_000L, false),
            Case(100_000L, 599_999L, false),
            Case(100_000L, 600_000L, true),
            Case(100_000L, -1L, false),
            Case(200_000L, 60_000L, true, ConversationLifecycleConfig(200, 1)),
            Case(199_999L, 60_000L, false, ConversationLifecycleConfig(200, 1)),
            Case(200_000L, 600_000L, false, domain = ConversationDomain.REALTIME),
            Case(200_000L, 600_000L, false, ConversationLifecycleConfig(Int.MAX_VALUE, Int.MAX_VALUE)),
        ).forEach { case ->
            val store = seed(isolatedContext(), listOf(ConversationSession(
                id = UUID.randomUUID().toString(), updatedAt = now - case.inactiveMs,
                contextUsageTokens = case.tokens, domain = case.domain,
            )))
            assertEquals(case.toString(), case.rotate, store.startNewConversationIfCold(case.config, now) != null)
        }
    }

    @Test fun freshActivityDefersRotationAndConcurrentChecksCreateOnlyOneSession() {
        val now = System.currentTimeMillis()
        val store = seed(isolatedContext(), listOf(ConversationSession(
            id = "old", updatedAt = now - 600_000L, contextUsageTokens = 100_000L,
        )))
        store.addMessage("user", "新活动")
        val lastActive = store.currentConversationSummary().updatedAt
        assertNull(store.startNewConversationIfCold(ConversationLifecycleConfig(), lastActive))

        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = workers.invokeAll(List(8) {
                Callable { store.startNewConversationIfCold(ConversationLifecycleConfig(), lastActive + 600_000L) }
            })
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) != null })
            assertEquals(2, store.conversationSummaries().size)
        } finally {
            workers.shutdownNow()
        }
    }
}
