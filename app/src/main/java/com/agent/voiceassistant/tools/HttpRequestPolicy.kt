package com.agent.voiceassistant.tools

object HttpRequestPolicy {
    private val reference = Regex("\\{\\{credential\\.([A-Za-z0-9._-]+)\\.([A-Za-z0-9._-]+)\\}\\}")

    fun expand(profile: String?, text: String?, lookup: (String, String) -> String?): String? {
        if (text == null || !text.contains("{{credential.")) return text
        return reference.replace(text) { match ->
            val name = match.groupValues[1]
            require(name == profile) { "凭证引用的 profile 与请求 profile 不一致" }
            requireNotNull(lookup(name, match.groupValues[2])) { "凭据 profile $name 缺少键 ${match.groupValues[2]}" }
        }
    }

    fun profileNames(keys: Set<String>): List<String> = keys.mapNotNull { key ->
        when {
            key.endsWith(".entries") -> key.removeSuffix(".entries")
            key.endsWith(".base_url") || key.endsWith(".url_prefixes") -> null
            else -> key
        }
    }.distinct().sorted()

    fun attempts(method: String): Int = if (method in setOf("GET", "HEAD")) 2 else 1

    fun headers(saved: Map<String, String>, explicit: Map<String, String>): Map<String, String> {
        val result = saved.toMutableMap()
        explicit.forEach { (name, value) ->
            val existing = result.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existing != null) {
                require(!name.equals("Authorization", true) || result[existing] == value) {
                    "显式 Authorization 与凭据 profile 的认证头冲突"
                }
                result.remove(existing)
            }
            result[name] = value
        }
        return result
    }
}
