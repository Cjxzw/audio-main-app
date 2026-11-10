package com.agent.voiceassistant.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Pure input policy: only an explicit password/key/token introduction is a signal. */
object SensitiveInputDetector {
    data class Hit(val start: Int, val end: Int, val value: String, val type: String)
    private val introduction = Regex(
        "(?i)(密码|口令|密钥|(?<![A-Za-z0-9_])(?:api[ _-]?key|access[ _-]?token|token|secret|password|key)(?![A-Za-z0-9_]))[\\\"'`]?\\s*(?:是|为|[:：=]|[ \t]+)\\s*",
    )

    fun detect(text: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        var consumed = 0
        introduction.findAll(text).forEach { match ->
            val start = match.range.last + 1
            if (start < consumed || start >= text.length || text.startsWith("{{", start)) return@forEach
            val quote = text[start]
            val close = when (quote) { '"', '\'', '`' -> quote; '“' -> '”'; '「' -> '」'; else -> null }
            val valueStart = if (close != null) start + 1 else start
            val end = if (close != null) (valueStart until text.length).firstOrNull { index ->
                if (text[index] != close) false else {
                    var backslashes = 0
                    var previous = index - 1
                    while (previous >= valueStart && text[previous] == '\\') { backslashes++; previous-- }
                    backslashes % 2 == 0
                }
            } ?: return@forEach
                else (valueStart until text.length).firstOrNull {
                    text[it].isWhitespace() || text[it] in "，。；;、,！？）】"
                } ?: text.length
            val value = text.substring(valueStart, end)
            if (value.isBlank() || value.startsWith("{{")) return@forEach
            val type = if (match.groupValues[1].lowercase() in listOf("密码", "口令", "password")) "password" else "key"
            hits += Hit(valueStart, end, value, type)
            consumed = end
        }
        return hits
    }
}

@Serializable
data class VaultField(val id: String = UUID.randomUUID().toString(), val name: String, val type: String, val value: String, val archived: Boolean = false)

@Serializable
data class VaultCredential(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sessionId: String,
    val automatic: Boolean = true,
    val referencedSessions: List<String> = listOf(sessionId),
    val createdAt: Long,
    val expiresAt: Long? = createdAt + CredentialPolicy.TTL_MS,
    val allowedUrls: List<String> = listOf("all"),
    val fields: List<VaultField>,
)

object CredentialPolicy {
    const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    val reference = Regex("\\{\\{cred:([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]+)}}")
    val anyReference = Regex("\\{\\{(?:cred:|credential\\.)[^}]*}}")
    fun token(record: VaultCredential, field: VaultField) = "{{cred:${record.id}.${field.id}}}"
    fun references(text: String) = reference.findAll(text).map { it.value }.distinct().toList()

    fun allows(url: String, whitelist: List<String>): Boolean {
        val target = url.toHttpUrlOrNull() ?: return false
        if (whitelist.isEmpty() || whitelist.any { it.isBlank() || it.equals("all", true) }) return true
        return whitelist.any { raw ->
            val prefix = raw.trim().toHttpUrlOrNull()
            if (prefix != null) {
                target.scheme == prefix.scheme && target.host == prefix.host && target.port == prefix.port &&
                    (target.encodedPath == prefix.encodedPath.trimEnd('/') || target.encodedPath.startsWith(prefix.encodedPath.trimEnd('/') + "/"))
            } else {
                // Bare hosts and host wildcards match the host only, never a URL substring.
                val pattern = raw.trim().lowercase()
                pattern.isNotEmpty() && !pattern.contains('/') && !pattern.contains('@') &&
                    Regex("^" + pattern.split('*').joinToString("[^/]*") { Regex.escape(it) } + "$").matches(target.host)
            }
        }
    }

    /** Replace string values after JSON parsing so quotes/newlines cannot alter its structure. */
    fun expandBody(body: String?, contentType: String?, expand: (String) -> String): String? {
        if (body == null || !anyReference.containsMatchIn(body)) return body
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
        return when {
            type == "application/json" || type.endsWith("+json") || type.isEmpty() -> {
                fun visit(value: JsonElement): JsonElement = when (value) {
                    is JsonObject -> JsonObject(value.mapValues { (key, child) ->
                        require(!anyReference.containsMatchIn(key)) { "凭证引用不能用作 JSON 键名" }
                        visit(child)
                    })
                    is JsonArray -> JsonArray(value.map(::visit))
                    is JsonPrimitive -> if (value.isString) JsonPrimitive(expand(value.content)) else value
                }
                visit(Json.parseToJsonElement(body)).toString()
            }
            type == "application/x-www-form-urlencoded" -> body.split('&').joinToString("&") { pair ->
                val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                require(!anyReference.containsMatchIn(key)) { "凭证引用不能用作表单键名" }
                val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(expand(value), "UTF-8")
            }
            else -> expand(body)
        }
    }
}
