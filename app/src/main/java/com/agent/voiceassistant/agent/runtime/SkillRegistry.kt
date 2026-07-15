package com.agent.voiceassistant.agent.runtime

import java.io.File

class SkillRegistry(
    private val skillsRoot: File,
) {
    data class Skill(
        val name: String,
        val description: String,
        val virtualPath: String,
    )

    fun list(): List<Skill> = skillsRoot.listFiles().orEmpty()
        .filter(File::isDirectory)
        .mapNotNull(::loadSkill)
        .sortedBy { it.name.lowercase() }

    fun promptSummary(): String {
        val skills = list()
        if (skills.isEmpty()) return "当前没有安装 Skill。"
        return buildString {
            appendLine("可用 Skill（只注入索引；需要使用时先通过 read 读取对应 SKILL.md）：")
            skills.forEach { skill ->
                appendLine("- ${skill.name}: ${skill.description} [${skill.virtualPath}]")
            }
        }.trimEnd()
    }

    private fun loadSkill(directory: File): Skill? {
        val file = File(directory, "SKILL.md")
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val metadata = parseFrontMatter(text)
        val name = metadata["name"]?.takeIf(String::isNotBlank) ?: directory.name
        val description = metadata["description"]?.takeIf(String::isNotBlank)
            ?: text.lineSequence()
                .dropWhile { it.isBlank() || it.trim() == "---" || it.contains(':') }
                .firstOrNull { it.isNotBlank() }
                ?.removePrefix("#")
                ?.trim()
            ?: "按需加载的本地工作流"
        return Skill(
            name = name.take(80),
            description = description.take(300),
            virtualPath = "/skills/${directory.name}/SKILL.md",
        )
    }

    private fun parseFrontMatter(text: String): Map<String, String> {
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return emptyMap()
        return lines.subList(1, end + 1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) return@mapNotNull null
            val key = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim().trim('"', '\'')
            if (key in setOf("name", "description")) key to value else null
        }.toMap()
    }
}
