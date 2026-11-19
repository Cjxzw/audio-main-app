package com.agent.voiceassistant.tools

import java.io.File
import java.nio.file.Files

internal class VirtualPathResolver(
    sourceRoot: File,
    logsRoot: File,
    workspaceRoot: File,
) {
    private data class Mount(val virtualRoot: String, val physicalRoot: File, val writable: Boolean)

    private val mounts = listOf(
        Mount("/source", sourceRoot.canonicalFile, false),
        Mount("/logs", logsRoot.canonicalFile, false),
        Mount("/workspace", workspaceRoot.canonicalFile, true),
    )

    fun resolve(path: String, write: Boolean): File {
        val normalized = normalize(path)
        val mount = mounts.firstOrNull { normalized == it.virtualRoot || normalized.startsWith("${it.virtualRoot}/") }
            ?: error("路径必须位于 /source、/logs 或 /workspace")
        if (write && !mount.writable) error("只允许写入 /workspace")
        val relative = normalized.removePrefix(mount.virtualRoot).trimStart('/')
        val target = File(mount.physicalRoot, relative).canonicalFile
        require(target.toPath().startsWith(mount.physicalRoot.toPath())) { "路径越界：$path" }
        return target
    }

    /** Archive traversal must not follow a link, even when its target is inside a mount. */
    fun resolveWithoutSymlinks(path: String, write: Boolean): File {
        val normalized = normalize(path)
        val target = resolve(normalized, write)
        val mount = mounts.first { normalized == it.virtualRoot || normalized.startsWith("${it.virtualRoot}/") }
        var current = mount.physicalRoot
        normalized.removePrefix(mount.virtualRoot).split('/').filter(String::isNotEmpty).forEach {
            current = File(current, it)
            require(!Files.isSymbolicLink(current.toPath())) { "ZIP 路径不能包含符号链接：$path" }
        }
        return target
    }

    fun normalize(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        val looksLikeVirtualPath = listOf("/source", "/logs", "/workspace")
            .any { root -> normalized == root || normalized.startsWith("$root/") }
        require(looksLikeVirtualPath) {
            "必须使用以 /source、/logs 或 /workspace 开头的绝对虚拟路径"
        }
        require(!normalized.split('/').contains("..")) { "路径不能包含 .." }
        return normalized.replace(Regex("/+"), "/").removeSuffix("/").ifEmpty { "/" }
    }
}
