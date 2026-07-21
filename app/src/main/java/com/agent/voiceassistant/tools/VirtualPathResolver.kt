package com.agent.voiceassistant.tools

import java.io.File

internal class VirtualPathResolver(
    sourceRoot: File,
    logsRoot: File,
    workspaceRoot: File,
    skillsRoot: File,
) {
    private data class Mount(val virtualRoot: String, val physicalRoot: File, val writable: Boolean)

    private val mounts = listOf(
        Mount("/source", sourceRoot.canonicalFile, false),
        Mount("/logs", logsRoot.canonicalFile, false),
        Mount("/workspace", workspaceRoot.canonicalFile, true),
        Mount("/skills", skillsRoot.canonicalFile, false),
    )

    fun resolve(path: String, write: Boolean): File {
        val normalized = normalize(path)
        val mount = mounts.firstOrNull { normalized == it.virtualRoot || normalized.startsWith("${it.virtualRoot}/") }
            ?: error("路径必须位于 /source、/logs、/workspace 或 /skills")
        if (write && !mount.writable) error("只允许写入 /workspace")
        val relative = normalized.removePrefix(mount.virtualRoot).trimStart('/')
        val target = File(mount.physicalRoot, relative).canonicalFile
        require(target.toPath().startsWith(mount.physicalRoot.toPath())) { "路径越界：$path" }
        return target
    }

    fun normalize(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        val looksLikeVirtualPath = normalized.startsWith("/source") ||
            normalized.startsWith("/logs") ||
            normalized.startsWith("/workspace") ||
            normalized.startsWith("/skills")
        require(looksLikeVirtualPath || File(path).isAbsolute) { "必须使用绝对虚拟路径或已知物理路径" }
        require(!normalized.split('/').contains("..")) { "路径不能包含 .." }
        val absolute = normalized.replace(Regex("/+"), "/").removeSuffix("/").ifEmpty { "/" }

        val physical = File(absolute).canonicalFile.toPath()
        mounts.firstOrNull { physical.startsWith(it.physicalRoot.toPath()) }?.let { mount ->
            val relative = mount.physicalRoot.toPath().relativize(physical).toString().replace('\\', '/')
            return if (relative.isBlank()) mount.virtualRoot else "${mount.virtualRoot}/$relative"
        }
        return absolute
    }
}
