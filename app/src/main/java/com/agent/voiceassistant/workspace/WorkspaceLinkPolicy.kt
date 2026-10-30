package com.agent.voiceassistant.workspace

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Resolves assistant/workspace links without handing virtual paths to another app. */
object WorkspaceLinkPolicy {
    private const val WORKSPACE_PREFIX = "/workspace/"
    private const val ASSET_HOST = "appassets.androidplatform.net"

    fun relativePath(link: String, basePath: String? = null): String? {
        val trimmed = link.trim()
        if (trimmed.isBlank()) return null
        val path = extractPath(trimmed) ?: return null
        val decoded = decode(path).replace('\\', '/')
        val relative = when {
            decoded.startsWith(WORKSPACE_PREFIX) -> decoded.removePrefix(WORKSPACE_PREFIX)
            decoded == "/workspace" -> ""
            decoded.startsWith("workspace/") -> decoded.removePrefix("workspace/")
            decoded.startsWith('/') -> return null
            basePath != null -> {
                val directory = basePath.substringBeforeLast('/', "")
                listOf(directory, decoded).filter(String::isNotBlank).joinToString("/")
            }
            else -> return null
        }
        return normalize(relative)
    }

    private fun extractPath(link: String): String? {
        val uri = runCatching { URI(link) }.getOrNull()
        if (uri != null && uri.scheme != null) {
            return when {
                uri.scheme.equals("hanwo", ignoreCase = true) &&
                    uri.host.equals("workspace", ignoreCase = true) ->
                    "$WORKSPACE_PREFIX${uri.rawPath.orEmpty().trimStart('/')}"
                uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true) -> {
                    if (uri.host.equals(ASSET_HOST, ignoreCase = true)) uri.rawPath.orEmpty() else null
                }
                else -> null
            }
        }
        return link.substringBefore('#').substringBefore('?').takeIf { it.isNotBlank() }
    }

    private fun decode(value: String): String = runCatching {
        // URLDecoder implements form encoding and would turn a literal '+' in a
        // filename into a space. Workspace links are URI paths, so keep '+'.
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrElse { value }

    private fun normalize(relative: String): String? {
        val result = ArrayDeque<String>()
        relative.split('/').forEach { segment ->
            when {
                segment.isBlank() || segment == "." -> Unit
                segment == ".." -> if (result.isEmpty()) return null else result.removeLast()
                else -> result.addLast(segment)
            }
        }
        return result.joinToString("/").takeIf { it.isNotBlank() }
    }
}
