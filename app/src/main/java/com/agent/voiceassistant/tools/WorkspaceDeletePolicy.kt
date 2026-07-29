package com.agent.voiceassistant.tools

internal object WorkspaceDeletePolicy {
    private val destructiveCommand = Regex(
        "(?:^|[;&|\\s])(?:[^;&|\\s]*/)?(?:rm|rmdir|unlink)(?:\\s|$)|" +
            "(?:^|[;&|\\s])(?:busybox|toybox|command|xargs)\\s+(?:rm|rmdir|unlink)(?:\\s|$)|" +
            "(?:^|[;&|\\s])find\\b[^\\n;]*\\s-delete(?:\\s|$)",
        RegexOption.IGNORE_CASE,
    )

    fun attemptsDirectDeletion(command: String): Boolean = destructiveCommand.containsMatchIn(command)
}
