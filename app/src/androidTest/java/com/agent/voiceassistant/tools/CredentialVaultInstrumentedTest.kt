package com.agent.voiceassistant.tools

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.ui.ChatMessage
import com.agent.voiceassistant.ui.ChatRole
import com.agent.voiceassistant.ui.CredentialTextRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.FutureTask

@RunWith(AndroidJUnit4::class)
class CredentialVaultInstrumentedTest {
    private fun isolatedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "credential-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
    }

    @Test fun captureDedupExpiryAndExplicitRestoreAreSessionBound() {
        val context = isolatedContext()
        var clock = System.currentTimeMillis()
        val vault = CredentialVault(context) { clock }
        val conversations = ConversationStore(context)
        val session = conversations.currentConversationId
        val secret = "dummy-test-${UUID.randomUUID()}"
        val first = vault.capture("密码是 $secret", session)
        val token = first.references.single()
        assertFalse(first.text.contains(secret))
        assertFalse(File(context.filesDir, "credential-vault.enc").readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        val expiry = vault.lookup(token)!!.first.expiresAt
        clock += 1000
        assertEquals(token, vault.capture("key=$secret", "other-session").references.single())
        assertEquals(expiry, vault.lookup(token)!!.first.expiresAt)
        vault.removeAutomaticField(token, session)
        assertNotNull(vault.lookup(token))
        assertFalse(vault.describe("", 10).contains(secret))
        conversations.addMessage("user", first.text, credentialReferences = first.references)
        val message = conversations.recentChatMessages().last()
        assertTrue(CredentialTextRenderer.render(context, message).toString().contains(secret))
        assertEquals(first.text, CredentialTextRenderer.render(context, message.copy(role = ChatRole.BOT)).toString())

        assertTrue(conversations.restoreCredential(session, token, secret))
        assertEquals(1, conversations.pendingCredentialNotices(session).size)
        assertFalse(conversations.llmHistory().any { it.content.orEmpty().contains(secret) })
        assertEquals("密码是 $secret", vault.capture("密码是 $secret", session, conversations.restoredCredentialValues(session).values.toSet()).text)
        assertFalse(vault.capture("密码是 $secret", "other-session").text.contains(secret))
        val pending = conversations.pendingCredentialNotices(session).single()
        assertEquals(1, conversations.acknowledgeCredentialNotices(session, setOf(pending.content)).size)
        assertTrue(conversations.pendingCredentialNotices(session).isEmpty())
        assertTrue(conversations.acknowledgeCredentialNotices(session, setOf(pending.content)).isEmpty())
        assertTrue(conversations.llmHistory().any { it.content == pending.content })
        assertEquals(secret, ConversationStore(context).restoredCredentialValues(session)[token])

        clock = expiry!!
        assertNull(vault.lookup(token))
        assertTrue(vault.records().isEmpty())
        assertTrue(runCatching { vault.resolve(token, "http://localhost") }.isFailure)
        assertEquals(first.text, CredentialTextRenderer.render(context, message.copy(conversationId = "other-session")).toString())
    }

    @Test fun httpExpandsJsonAndRedactsEcho() = runBlocking {
        val context = isolatedContext()
        val vault = CredentialVault(context)
        val secret = "dummy\"&=+\nline-${UUID.randomUUID()}"
        val field = VaultField(name = "password1", type = "password", value = secret)
        val record = VaultCredential(name = "http-test", sessionId = "test", createdAt = System.currentTimeMillis(), fields = listOf(field))
        vault.save(record)
        val token = CredentialPolicy.token(record, field)
        ServerSocket(0).use { server ->
            val received = FutureTask {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.getInputStream().bufferedReader()
                    input.readLine()
                    var size = 0
                    while (true) {
                        val header = input.readLine().orEmpty()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(size)
                    var offset = 0
                    while (offset < size) { val count = input.read(chars, offset, size - offset); check(count > 0); offset += count }
                    val body = Json.parseToJsonElement(String(chars)).jsonObject
                    val value = body.getValue("password").jsonPrimitive.content
                    val response = buildJsonObject { put("echo", value) }.toString().toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray() + response)
                    value
                }
            }
            Thread(received).start()
            val env = AndroidExecutionEnv(context)
            val response = env.httpRequest("POST", "http://127.0.0.1:${server.localPort}/echo", "{\"password\":\"$token\"}", "application/json")
            assertEquals(secret, received.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(200, response.status)
            assertTrue(response.body.contains(token))
            assertFalse(response.body.contains("dummy"))
            assertTrue(runCatching { env.write("/workspace/test.txt", token) }.isFailure)
            assertTrue(runCatching { env.exec(listOf("echo", token)) }.isFailure)
        }
    }
}
