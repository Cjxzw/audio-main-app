package com.agent.voiceassistant.tools

/** Keep credential bytes out of shell source and let the shell expand one quoted environment value. */
internal object ScriptCredentials {
    data class Expansion(val source: String, val environment: Map<String, String>)
    fun expand(source: String, prefix: String = "HANWO_CREDENTIAL_", resolve: (String) -> String): Expansion {
        val values = linkedMapOf<String, String>()
        val names = linkedMapOf<String, String>()
        val result = StringBuilder()
        var quote: Char? = null
        var index = 0
        while (index < source.length) {
            if (quote == null && source[index] == '#' && (index == 0 || source[index - 1].isWhitespace() || source[index - 1] in ";|&")) {
                val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                result.append(source.substring(index, end))
                index = end
                continue
            }
            val match = CredentialPolicy.anyReference.find(source, index)?.takeIf { it.range.first == index }
            if (match != null) {
                val name = names.getOrPut(match.value) {
                    "$prefix${names.size}".also { values[it] = resolve(match.value) }
                }
                val expansion = "\${$name}"
                result.append(when (quote) {
                    '\'' -> "'\"$expansion\"'"
                    '"' -> expansion
                    else -> "\"$expansion\""
                })
                index = match.range.last + 1
                continue
            }
            val ch = source[index]
            result.append(ch)
            if (ch == '\\' && quote != '\'' && index + 1 < source.length) {
                result.append(source[++index])
            } else if (ch == '\'' || ch == '"') {
                if (quote == null) quote = ch else if (quote == ch) quote = null
            }
            index++
        }
        return Expansion(result.toString(), values)
    }
}
