package com.agent.voiceassistant.integration

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.cloud.NetworkTimeoutException
import com.agent.voiceassistant.data.ConversationDomain
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.tools.AndroidExecutionEnv
import com.agent.voiceassistant.tools.CredentialProfileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** All test data lives under an isolated cache directory and isolated preferences. */
@RunWith(AndroidJUnit4::class)
class MainRealtimeIntegrationTest {
    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "integration-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            private val directory = File(base.cacheDir, name).apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$name-$key", mode)
        }
    }

    @Test fun longTranscriptIsTransferredOnceAndSurvivesLaterMainTurns() {
        val context = isolatedContext()
        val store = ConversationStore(context)
        val main = store.currentConversationId
        val realtime = store.createDetachedConversation(ConversationDomain.REALTIME).id
        repeat(60) {
            store.addMessageToConversation(realtime, "user", "问题$it")
            store.addMessageToConversation(realtime, "assistant", "完整正文$it " + "长".repeat(1000))
        }
        store.transferRealtimeTranscript(realtime, main)
        val notes = store.realtimeContextNotes(main)
        assertEquals(1, notes.size)
        assertTrue(notes.single().contains("问题0"))
        assertTrue(notes.single().contains("完整正文59 " + "长".repeat(1000)))
        store.transferRealtimeTranscript(realtime, main)
        assertEquals(notes, store.realtimeContextNotes(main))
        store.addMessageToConversation(realtime, "user", "重连后的新问题")
        store.transferRealtimeTranscript(realtime, main)
        val finalNotes = store.realtimeContextNotes(main)
        assertEquals(2, finalNotes.size)
        assertFalse(finalNotes.last().contains("问题0"))
        assertTrue(finalNotes.last().contains("重连后的新问题"))
        val persisted = File(context.filesDir, "main-agent-store.json").readText()
        assertTrue(persisted.contains("realtimeHandoverCount"))
        assertTrue(persisted.contains("完整正文59"))
        assertEquals(finalNotes, ConversationStore(context).realtimeContextNotes(main))
    }

    @Test fun credentialsRoundTripMultipleEntriesRelativeUrlAndDeletion() {
        val store = CredentialProfileStore(isolatedContext())
        store.putEntries("modern", mapOf("key" to "test-value", "extra" to "second-value"), listOf("https://example.com"), "https://example.com")
        assertEquals(listOf("modern"), store.availableProfiles().map { it.name })
        assertEquals(2, store.entries("modern").size)
        assertEquals("https://example.com/api/test", store.resolveUrl("modern", "/api/test"))
        assertEquals("Bearer test-value", store.expandReferences("modern", "Bearer {{credential.modern.key}}"))
        assertEquals("plain } text", store.expandReferences(null, "plain } text"))
        store.putHeaders("legacy", mapOf("Authorization" to "Bearer old"))
        store.putEntries("legacy", mapOf("Authorization" to "Bearer updated", "extra" to "two"), listOf("https://example.com"), "https://example.com")
        assertEquals("Bearer updated", store.headers("legacy")["Authorization"])
        store.delete("modern")
        store.delete("legacy")
        assertTrue(store.availableProfiles().isEmpty())
        assertTrue(store.headers("legacy").isEmpty())
    }

    @Test fun httpPostExpandsCredentialsAndReturnsResponseHeaders() = runBlocking {
        val context = isolatedContext()
        val credentials = CredentialProfileStore(context)
        ServerSocket(0).use { server ->
            val base = "http://127.0.0.1:${server.localPort}"
            credentials.putHeaders("local", mapOf("Authorization" to "Bearer test-auth"))
            credentials.putEntries("local", mapOf("Authorization" to "Bearer test-auth", "key" to "test-body"), listOf(base), base)
            var received = ""
            val worker = thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                    val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) { val n = reader.read(body, offset, length - offset); if (n < 0) break; offset += n }
                    received = lines.joinToString("\n") + "\n" + String(body)
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nX-Test: present\r\nConnection: close\r\n\r\nOK".toByteArray())
                }
            }
            val result = AndroidExecutionEnv(context, credentials).httpRequest("POST", "/api/test", body = "{\"key\":\"{{credential.local.key}}\"}", credentialProfile = "local", headers = mapOf("Accept" to "application/json"), timeoutMs = 3000)
            worker.join(5000)
            assertEquals(200, result.status)
            assertTrue(received.contains("Authorization: Bearer test-auth"))
            assertTrue(received.contains("{\"key\":\"test-body\"}"))
            assertTrue(result.headers.entries.any { it.key.equals("X-Test", true) && it.value == listOf("present") })
        }
    }

    @Test fun timedOutPostIsSentOnlyOnce() = runBlocking {
        val context = isolatedContext()
        val count = AtomicInteger()
        ServerSocket(0).use { server ->
            server.soTimeout = 2500
            val worker = thread {
                runCatching {
                    while (true) {
                        val socket = server.accept()
                        count.incrementAndGet()
                        thread { socket.use { Thread.sleep(1600) } }
                    }
                }
            }
            val env = AndroidExecutionEnv(context)
            try {
                env.httpRequest("POST", "http://127.0.0.1:${server.localPort}/write", body = "{}", timeoutMs = 1000)
                fail("Expected a timeout")
            } catch (_: NetworkTimeoutException) { }
            worker.join(4000)
            assertEquals(1, count.get())
        }
    }
}
