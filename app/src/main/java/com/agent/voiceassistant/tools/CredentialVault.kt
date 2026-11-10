package com.agent.voiceassistant.tools

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted atomic document. No plaintext values or fingerprints in preferences/indexes. */
class CredentialVault(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "credential-vault.enc"))
    private val lock = locks.getOrPut(file.baseFile.absolutePath) { Any() }
    private val json = Json { ignoreUnknownKeys = true }
    data class Capture(val text: String, val references: List<String>)

    private fun <T> access(block: (MutableList<VaultCredential>) -> T): T = synchronized(lock) {
        val records = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            json.decodeFromString<List<VaultCredential>>(decrypt(file.readFully())).toMutableList()
        } else mutableListOf()
        val before = json.encodeToString(records)
        records.removeAll { it.expiresAt?.let { expiry -> expiry <= now() } == true }
        try { block(records) } finally {
            val after = json.encodeToString(records)
            if (before != after) {
                val encrypted = encrypt(after)
                val stream = file.startWrite()
                try { stream.write(encrypted); file.finishWrite(stream) }
                catch (e: Exception) { file.failWrite(stream); throw e }
            }
        }
    }

    fun capture(text: String, sessionId: String, exemptValues: Set<String> = emptySet()): Capture {
        val hits = SensitiveInputDetector.detect(text).filterNot { it.value in exemptValues }
        if (hits.isEmpty() && !CredentialPolicy.reference.containsMatchIn(text)) return Capture(text, emptyList())
        return access { records ->
            val result = StringBuilder()
            var cursor = 0
            hits.forEach { hit ->
                val existing = records.firstNotNullOfOrNull { record ->
                    record.fields.firstOrNull { !it.archived && it.value == hit.value }?.let { record to it }
                }
                val (record, field) = existing ?: run {
                    val prefix = "s${sessionId.filter(Char::isLetterOrDigit).take(6)}_${hit.type}"
                    val number = generateSequence(1) { it + 1 }.first { n -> records.none { it.name == "$prefix$n" } }
                    val f = VaultField(name = "${hit.type}1", type = hit.type, value = hit.value)
                    val r = VaultCredential(name = "$prefix$number", sessionId = sessionId, createdAt = now(), fields = listOf(f))
                    records += r
                    r to f
                }
                result.append(text, cursor, hit.start).append(CredentialPolicy.token(record, field))
                cursor = hit.end
            }
            result.append(text, cursor, text.length)
            val sanitized = result.toString()
            val references = CredentialPolicy.references(sanitized).filter { token -> find(records, token) != null }
            references.forEach { token ->
                val record = find(records, token)!!.first
                if (sessionId !in record.referencedSessions) {
                    records[records.indexOf(record)] = record.copy(referencedSessions = record.referencedSessions + sessionId)
                }
            }
            Capture(sanitized, references)
        }
    }

    fun records(): List<VaultCredential> = access { it.toList() }
    fun get(id: String): VaultCredential? = access { records -> records.firstOrNull { it.id == id } }
    fun lookup(token: String): Pair<VaultCredential, VaultField>? = access { find(it, token) }
    fun resolve(token: String, url: String): String = access { records ->
        val (record, field) = find(records, token) ?: error("凭证引用不存在或已到期：$token")
        require(CredentialPolicy.allows(url, record.allowedUrls)) { "URL 不在凭证 ${record.name} 的允许范围内" }
        field.value
    }
    fun save(record: VaultCredential) = access { records ->
        require(record.name.isNotBlank()) { "请填写凭证名称" }
        require(record.fields.any { !it.archived }) { "凭证至少需要一个键值" }
        require(record.fields.filterNot { it.archived }.all { it.name.isNotBlank() && it.value.isNotEmpty() }) { "键名和值不能为空" }
        require(record.expiresAt == null || record.expiresAt > now()) { "凭证已到期，请创建新凭证" }
        val index = records.indexOfFirst { it.id == record.id }
        if (index < 0) records.add(record) else records[index] = record.copy(referencedSessions = (records[index].referencedSessions + record.referencedSessions).distinct())
    }
    fun delete(id: String) = access { it.removeAll { record -> record.id == id } }
    fun removeAutomaticField(token: String, sessionId: String) = access { records ->
        val (record, field) = find(records, token) ?: return@access
        if (!record.automatic || record.referencedSessions.any { it != sessionId }) return@access
        val fields = record.fields.filterNot { it.id == field.id }
        records.remove(record)
        if (fields.isNotEmpty()) records += record.copy(fields = fields)
    }

    fun describe(query: String, limit: Int): String = access { records ->
        val matching = records.filter { record -> query.isBlank() ||
            (listOf(record.name, record.id) + record.fields.filterNot { it.archived }.map { it.name }).any { it.contains(query, true) }
        }
        matching.sortedByDescending { it.createdAt }.take(limit.coerceIn(1, 20)).joinToString("\n") { record ->
            "${record.name} uid=${record.id} expires=${record.expiresAt ?: "permanent"} urls=${record.allowedUrls.joinToString(",")}\n" +
                record.fields.filterNot { it.archived }.joinToString("\n") { field -> "  ${field.name} (${field.type}) ${CredentialPolicy.token(record, field)}" }
        }.ifBlank { "没有匹配的有效凭证。" }
    }

    private fun find(records: List<VaultCredential>, token: String): Pair<VaultCredential, VaultField>? {
        val match = CredentialPolicy.reference.matchEntire(token) ?: return null
        val record = records.firstOrNull { it.id == match.groupValues[1] } ?: return null
        return record.fields.firstOrNull { it.id == match.groupValues[2] }?.let { record to it }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(text: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key()); iv + doFinal(text.toByteArray(Charsets.UTF_8))
    }
    private fun decrypt(bytes: ByteArray): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(bytes.size > 12) { "凭证库损坏" }
        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    private companion object {
        const val KEY_ALIAS = "hanwo-automatic-credential-vault-v1"
        val locks = ConcurrentHashMap<String, Any>()
    }
}
