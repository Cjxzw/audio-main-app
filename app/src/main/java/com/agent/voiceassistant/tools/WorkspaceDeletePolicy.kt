package com.agent.voiceassistant.tools

internal object WorkspaceDeletePolicy {
    private val destructiveCommand = Regex(
        "(?:^|[;&|\\s])(?:[^;&|\\s]*/)?(?:rm|rmdir|unlink)(?:\\s|$)|" +
            "(?:^|[;&|\\s])(?:busybox|toybox|command|xargs)\\s+(?:rm|rmdir|unlink)(?:\\s|$)|" +
            "(?:^|[;&|\\s])find\\b[^\\n;]*\\s-delete(?:\\s|$)",
        RegexOption.IGNORE_CASE,
    )

    fun attemptsDirectDeletion(command: String): Boolean = destructiveCommand.containsMatchIn(command)

    fun attemptsDirectDeletion(argv: List<String>): Boolean {
        val executable = argv.firstOrNull()
            ?.substringAfterLast('/')
            ?.lowercase()
            ?: return false
        return executable in DESTRUCTIVE_EXECUTABLES ||
            executable == "find" && argv.any { it == "-delete" }
    }

    fun attemptsShellExecution(argv: List<String>): Boolean = argv.firstOrNull()
        ?.substringAfterLast('/')
        ?.lowercase() in SHELL_OR_MULTICALL_EXECUTABLES

    private val DESTRUCTIVE_EXECUTABLES = setOf("rm", "rmdir", "unlink")
    private val SHELL_OR_MULTICALL_EXECUTABLES = setOf(
        "sh",
        "bash",
        "zsh",
        "dash",
        "ash",
        "busybox",
        "toybox",
        "xargs",
    )
}
