package com.agent.voiceassistant.tools

import org.junit.Assert.*
import org.junit.Test

class HttpRequestPolicyTest {
    @Test fun expandsReferencesInHeadersAndJsonWithoutParsingPlainTextAsRegex() {
        assertEquals("ordinary } text", HttpRequestPolicy.expand(null, "ordinary } text") { _, _ -> null })
        assertNull(HttpRequestPolicy.expand(null, null) { _, _ -> null })
        assertEquals("Bearer value", HttpRequestPolicy.expand("metaso", "Bearer {{credential.metaso.key}}") { _, _ -> "value" })
        assertEquals("{\"key\":\"value\"}", HttpRequestPolicy.expand("metaso", "{\"key\":\"{{credential.metaso.key}}\"}") { _, _ -> "value" })
    }

    @Test fun referencesCannotCrossProfilesOrUseMissingKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpRequestPolicy.expand("one", "{{credential.two.key}}") { _, _ -> "value" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpRequestPolicy.expand("one", "{{credential.one.key}}") { _, _ -> null }
        }
    }

    @Test fun enumeratesModernAndLegacyProfilesWithoutMetadataDuplicates() {
        assertEquals(listOf("legacy", "metaso"), HttpRequestPolicy.profileNames(setOf(
            "metaso.entries", "metaso.url_prefixes", "metaso.base_url", "legacy", "legacy.base_url")))
    }

    @Test fun ordinaryExplicitHeadersPreserveAuthenticationAndOverrideCaseInsensitively() {
        val merged = HttpRequestPolicy.headers(mapOf("Authorization" to "Bearer saved", "Accept" to "old"),
            mapOf("accept" to "new"))
        assertEquals("Bearer saved", merged["Authorization"])
        assertEquals("new", merged["accept"])
        assertEquals(2, merged.size)
        assertThrows(IllegalArgumentException::class.java) {
            HttpRequestPolicy.headers(mapOf("Authorization" to "Bearer saved"), mapOf("authorization" to "other"))
        }
    }

    @Test fun legacyPrefixCanActAsSafeRelativeUrlBase() {
        val prefix = "https://metaso.cn/".trimEnd('/')
        assertEquals("https://metaso.cn/api/open/search", "$prefix/api/open/search")
    }

    @Test fun mutationsAreNeverAutomaticallyRetried() {
        listOf("POST", "PUT", "PATCH", "DELETE").forEach { assertEquals(1, HttpRequestPolicy.attempts(it)) }
        assertEquals(2, HttpRequestPolicy.attempts("GET"))
    }
}
