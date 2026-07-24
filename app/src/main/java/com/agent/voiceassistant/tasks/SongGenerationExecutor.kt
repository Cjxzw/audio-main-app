package com.agent.voiceassistant.tasks

import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.cloud.VoicePerformance
import com.agent.voiceassistant.cloud.VoiceReplyMode
import com.agent.voiceassistant.cloud.VoiceReplyOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class SongGenerationExecutor(
    private val workspaceRoot: File,
    private val clientProvider: suspend () -> CloudSpeechClient,
) : TaskExecutor {
    override val taskType = TYPE
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(task: TaskEntity, progress: suspend (Int) -> Unit): TaskExecutionResult =
        withTimeout(TOTAL_TIMEOUT_MS) {
            val payload = json.parseToJsonElement(task.inputJson) as JsonObject
            val lyrics = payload.text("lyrics") ?: error("缺少歌词")
            require(lyrics.length <= MAX_LYRICS_CHARS) { "歌词不能超过 $MAX_LYRICS_CHARS 字" }
            val voice = payload.text("voice") ?: VoiceReplyOptions.DEFAULT_VOICE
            require(voice in VoiceReplyOptions.PRESET_VOICES) { "不支持的唱歌音色：$voice" }
            val title = sanitizeTitle(payload.text("title") ?: "未命名歌曲")
            val stylePrompt = payload.text("style_prompt")
            val directory = File(workspaceRoot, SONG_DIRECTORY).apply { mkdirs() }
            val pcmPart = File(directory, ".${task.taskId}.part")
            val wavPart = File(directory, ".${task.taskId}.wav.part")
            val output = uniqueOutput(directory, title, task.taskId)
            var bytesWritten = 0L
            val startedAt = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    FileOutputStream(pcmPart).use { stream ->
                        val streamed = clientProvider().streamSynthesizeSpeech(
                            text = lyrics,
                            options = VoiceReplyOptions(
                                mode = VoiceReplyMode.PRESET,
                                voice = voice,
                                performance = VoicePerformance.SINGING,
                                stylePrompt = stylePrompt,
                            ),
                            firstAudioTimeoutMs = FIRST_AUDIO_TIMEOUT_MS,
                        ) { audio ->
                            val pcm = extractPcm(audio.bytes)
                            if (pcm.isNotEmpty()) {
                                if (bytesWritten + pcm.size > MAX_AUDIO_BYTES) error("歌曲音频超过大小限制")
                                stream.write(pcm)
                                bytesWritten += pcm.size
                                progress((10 + bytesWritten / 128_000L).toInt().coerceIn(10, 95))
                            }
                        }
                        check(streamed && bytesWritten > 0) { "唱歌服务没有返回音频" }
                    }
                    writeWav(pcmPart, wavPart, SAMPLE_RATE)
                    check(wavPart.renameTo(output)) { "无法保存歌曲文件" }
                }
                val durationSeconds = bytesWritten / (SAMPLE_RATE * 2L)
                val virtualPath = "/workspace/$SONG_DIRECTORY/${output.name}"
                Timber.i(
                    "SongTask completed task=${task.taskId} voice=$voice lyricsChars=${lyrics.length} " +
                        "stylePromptChars=${stylePrompt?.length ?: 0} totalMs=${System.currentTimeMillis() - startedAt} " +
                        "output=$virtualPath",
                )
                TaskExecutionResult(
                    summary = "歌曲《$title》已生成",
                    details = "文件：$virtualPath\n时长约 ${durationSeconds} 秒",
                    outputPath = virtualPath,
                    artifacts = listOf(
                        TaskArtifactEntity(
                            artifactId = "artifact_${UUID.randomUUID()}",
                            taskId = task.taskId,
                            name = output.name,
                            path = virtualPath,
                            mimeType = "audio/wav",
                            size = output.length(),
                            sha256 = sha256(output),
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            } finally {
                pcmPart.delete()
                wavPart.delete()
            }
        }

    override suspend fun cleanup(task: TaskEntity) {
        val directory = File(workspaceRoot, SONG_DIRECTORY)
        File(directory, ".${task.taskId}.part").delete()
        File(directory, ".${task.taskId}.wav.part").delete()
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotBlank)

    private fun extractPcm(bytes: ByteArray): ByteArray {
        if (bytes.size < 44 || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF") return bytes
        var offset = 12
        while (offset <= bytes.size - 8) {
            val size = readLeInt(bytes, offset + 4)
            if (size < 0) return ByteArray(0)
            if (bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII) == "data") {
                val start = offset + 8
                return bytes.copyOfRange(start, (start + size).coerceAtMost(bytes.size))
            }
            offset += 8 + size + (size and 1)
        }
        return ByteArray(0)
    }

    private fun writeWav(pcm: File, target: File, sampleRate: Int) {
        FileOutputStream(target).use { output ->
            val dataSize = pcm.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            output.write(wavHeader(dataSize, sampleRate))
            FileInputStream(pcm).use { it.copyTo(output) }
        }
    }

    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray = ByteArray(44).also { header ->
        fun text(offset: Int, value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        fun le16(offset: Int, value: Int) {
            header[offset] = value.toByte(); header[offset + 1] = (value ushr 8).toByte()
        }
        fun le32(offset: Int, value: Int) {
            repeat(4) { header[offset + it] = (value ushr (8 * it)).toByte() }
        }
        text(0, "RIFF"); le32(4, dataSize + 36); text(8, "WAVE"); text(12, "fmt ")
        le32(16, 16); le16(20, 1); le16(22, 1); le32(24, sampleRate)
        le32(28, sampleRate * 2); le16(32, 2); le16(34, 16); text(36, "data"); le32(40, dataSize)
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or (bytes[offset + 3].toInt() shl 24)

    private fun sanitizeTitle(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().take(48).ifBlank { "未命名歌曲" }

    private fun uniqueOutput(directory: File, title: String, taskId: String): File =
        File(directory, "$title-${taskId.takeLast(8)}.wav")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TYPE = "song_generation"
        const val SONG_DIRECTORY = "唱歌"
        private const val SAMPLE_RATE = 24_000
        private const val MAX_LYRICS_CHARS = 2_000
        private const val MAX_AUDIO_BYTES = 120L * 1024L * 1024L
        private const val FIRST_AUDIO_TIMEOUT_MS = 120_000L
        private const val TOTAL_TIMEOUT_MS = 10L * 60L * 1_000L
    }
}
