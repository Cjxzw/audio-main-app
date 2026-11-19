package com.agent.voiceassistant.tools

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal object BinaryFileCodec {
    fun requireEncoding(encoding: String) = require(encoding in setOf("utf8", "base64", "hex")) { "encoding 必须是 utf8、base64 或 hex" }
    fun decoder() = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    fun utf8(bytes: ByteArray): String = try { decoder().decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { error("文件不是有效 UTF-8，请使用 encoding=base64 或 hex") }

    fun encode(bytes: ByteArray, encoding: String): String = when (encoding) {
        "utf8" -> utf8(bytes)
        "base64" -> Base64.getEncoder().encodeToString(bytes)
        "hex" -> bytes.joinToString("") { "%02x".format(it) }
        else -> error("未知 encoding：$encoding")
    }

    fun decode(content: String, encoding: String): ByteArray = when (encoding) {
        "utf8" -> content.toByteArray(Charsets.UTF_8)
        "base64" -> {
            val padding = when { content.endsWith("==") -> 2; content.endsWith("=") -> 1; else -> 0 }
            val dataLength = content.length - padding
            require(content.length % 4 == 0 && (0 until dataLength).all { content[it] in 'A'..'Z' || content[it] in 'a'..'z' || content[it] in '0'..'9' || content[it] == '+' || content[it] == '/' } && (dataLength until content.length).all { content[it] == '=' }) { "无效 base64 编码" }
            Base64.getDecoder().decode(content).also { require(Base64.getEncoder().encodeToString(it) == content) { "非规范 base64 编码" } }
        }
        "hex" -> {
            require(content.length % 2 == 0 && content.all { it in "0123456789abcdefABCDEF" }) { "无效 hex 编码" }
            ByteArray(content.length / 2) { content.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        else -> error("未知 encoding：$encoding")
    }
}
