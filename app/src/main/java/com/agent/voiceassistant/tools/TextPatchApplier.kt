package com.agent.voiceassistant.tools

internal object TextPatchApplier {
    fun apply(
        current: String,
        replacement: String,
        startLine: Int?,
        endLine: Int?,
    ): String {
        require(startLine != null && endLine != null) { "patch 模式需要 start_line 和 end_line" }
        require(startLine >= 1 && endLine >= startLine) { "patch 行号范围无效" }
        val lines = current.split("\n").toMutableList()
        require(endLine <= lines.size) { "patch 行号超出文件范围：$startLine-$endLine/${lines.size}" }
        val replacementLines = replacement.split("\n")
        lines.subList(startLine - 1, endLine).clear()
        lines.addAll(startLine - 1, replacementLines)
        return lines.joinToString("\n")
    }
}
