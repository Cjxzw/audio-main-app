package com.agent.voiceassistant.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class CredentialPolicyTest {
    @Test fun `only explicit introductions detect values and existing tokens stay intact`() {
        assertTrue(SensitiveInputDetector.detect("随机文本 sk-abcdefghijklmnopqrstuvwxyz 12345678901234567890").isEmpty())
        val text = "密码是 P@ss123!?；API key: \"a b&c\"，token={{cred:record.field}}"
        val hits = SensitiveInputDetector.detect(text)
        assertEquals(listOf("P@ss123!?", "a b&c"), hits.map { it.value })
        hits.forEach { assertEquals(it.value, text.substring(it.start, it.end)) }
        assertEquals(listOf("password", "key"), hits.map { it.type })
        assertTrue(SensitiveInputDetector.detect("这是 monkey: banana，keyboard: qwerty").isEmpty())
        assertEquals("abc!", SensitiveInputDetector.detect("token abc!").single().value)
    }

    @Test fun `json expansion preserves structure and form expansion preserves one value`() {
        val token = "{{cred:a.b}}"
        val secret = "quote\" &plus+ =line\n\\tail"
        val input = buildJsonObject { put("password", token); put("number", 3); putJsonArray("nested") { add(token) } }.toString()
        val result = Json.parseToJsonElement(CredentialPolicy.expandBody(input, "application/json") { it.replace(token, secret) }!!).jsonObject
        assertEquals(secret, result.getValue("password").jsonPrimitive.content)
        assertEquals(3, result.getValue("number").jsonPrimitive.int)
        assertEquals(secret, result.getValue("nested").jsonArray[0].jsonPrimitive.content)
        val form = CredentialPolicy.expandBody("password=$token&x=ok", "application/x-www-form-urlencoded") { it.replace(token, secret) }!!
        assertEquals(2, form.split('&').size)
        assertEquals(secret, URLDecoder.decode(form.substringBefore('&').substringAfter('='), "UTF-8"))
    }

    @Test fun `url scope uses exact authority and path boundaries`() {
        assertTrue(CredentialPolicy.allows("http://any.example/a", listOf("all")))
        assertTrue(CredentialPolicy.allows("https://a.example.com/api/test", listOf("https://a.example.com/api")))
        assertFalse(CredentialPolicy.allows("https://a.example.com/apievil", listOf("https://a.example.com/api")))
        assertFalse(CredentialPolicy.allows("https://a.example.com.evil/api", listOf("https://a.example.com/api")))
        assertFalse(CredentialPolicy.allows("http://a.example.com/api", listOf("https://a.example.com/api")))
        assertFalse(CredentialPolicy.allows("https://a.example.com:8443/api", listOf("https://a.example.com/api")))
        assertTrue(CredentialPolicy.allows("https://sub.example.com/x", listOf("*.example.com")))
        assertFalse(CredentialPolicy.allows("https://example.com.evil/x", listOf("*.example.com")))
        assertFalse(CredentialPolicy.allows("file:///tmp/a", listOf("all")))
    }
}
