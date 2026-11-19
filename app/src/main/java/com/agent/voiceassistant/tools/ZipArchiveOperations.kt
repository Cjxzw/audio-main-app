package com.agent.voiceassistant.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** File streams stay in the host; only bounded summaries enter the JS heap. */
internal class ZipArchiveOperations(private val paths: VirtualPathResolver) {
    private data class Source(val file: File, val name: String, val directory: Boolean)

    @Synchronized
    fun zip(inputs: List<String>, outputPath: String, maxBytes: Long, overwrite: Boolean, deadlineNanos: Long): JsonObject {
        val deadline = deadline(deadlineNanos, maxBytes)
        require(inputs.size in 1..100) { "zip 的 paths 必须包含 1..100 个路径" }
        val destination = output(outputPath)
        require(!destination.isDirectory && (overwrite || !destination.exists())) { "ZIP 输出已存在，覆盖文件需 overwrite=true" }
        val sources = mutableListOf<Source>()
        val names = hashSetOf<String>()
        var inputBytes = 0L
        fun collect(file: File, name: String) {
            checkTime(deadline)
            require(!Files.isSymbolicLink(file.toPath())) { "zip 不支持符号链接" }
            require(file.isDirectory || file.isFile) { "zip 输入不存在或不是普通文件" }
            val directory = file.isDirectory
            validateName(name + if (directory) "/" else "", directory)
            require(names.add(name)) { "ZIP 内文件名冲突：$name；请改为打包共同父目录" }
            require(sources.size < MAX_ENTRIES) { "ZIP 最多包含 $MAX_ENTRIES 个文件和目录" }
            sources += Source(file, name, directory)
            if (directory) {
                val children = requireNotNull(file.listFiles()) { "无法读取 ZIP 输入目录" }
                require(children.size <= MAX_ENTRIES) { "ZIP 目录条目过多" }
                children.sortedBy { it.name }.forEach { collect(it, "$name/${it.name}") }
            } else {
                inputBytes += file.length()
                require(inputBytes <= MAX_BYTES) { "ZIP 输入总大小不能超过 128 MiB" }
            }
        }
        inputs.forEach { path ->
            val file = paths.resolveWithoutSymlinks(path, write = false)
            require(destination != file && (!file.isDirectory || !destination.toPath().startsWith(file.toPath()))) {
                "ZIP 输出不能位于输入文件或输入目录中"
            }
            collect(file, file.name)
        }
        require(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) { "无法创建 ZIP 输出父目录" }
        val temporary = File.createTempFile(".hanwo-zip-", ".tmp", destination.parentFile)
        try {
            var actualInputBytes = 0L
            ZipOutputStream(LimitedOutput(temporary.outputStream().buffered(), maxBytes, deadline), Charsets.UTF_8).use { archive ->
                sources.forEach { source ->
                    checkTime(deadline)
                    require(!Files.isSymbolicLink(source.file.toPath())) { "zip 输入已变为符号链接" }
                    archive.putNextEntry(ZipEntry(source.name + if (source.directory) "/" else ""))
                    if (!source.directory) source.file.inputStream().buffered().use { input ->
                        copy(input, archive, deadline, onBytes = { count ->
                            actualInputBytes += count
                            require(actualInputBytes <= MAX_BYTES) { "ZIP 输入总大小不能超过 128 MiB" }
                        })
                    }
                    archive.closeEntry()
                }
            }
            val result = buildJsonObject {
                put("output_path", paths.normalize(outputPath))
                put("bytes", temporary.length())
                put("input_bytes", actualInputBytes)
                put("entries", sources.size)
                put("files", sources.count { !it.directory })
                put("sha256", hash(temporary, deadline))
            }
            checkTime(deadline)
            paths.resolveWithoutSymlinks(outputPath, write = true)
            if (overwrite) Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(temporary.toPath(), destination.toPath())
            return result
        } finally { temporary.delete() }
    }

    @Synchronized
    fun unzip(path: String, outputDir: String, maxBytes: Long, maxEntries: Int, charsetName: String, deadlineNanos: Long): JsonObject {
        val deadline = deadline(deadlineNanos, maxBytes)
        require(maxEntries in 1..MAX_ENTRIES) { "max_entries 必须在 1..$MAX_ENTRIES 之间" }
        require(charsetName in setOf("UTF-8", "GBK", "GB18030")) { "filename_charset 只支持 UTF-8、GBK、GB18030" }
        val source = paths.resolveWithoutSymlinks(path, write = false)
        require(source.isFile && source.length() <= MAX_BYTES) { "unzip 输入必须是不超过 128 MiB 的 ZIP 文件" }
        val destination = output(outputDir)
        require(!destination.exists()) { "解压目录已存在，请使用新的 output_dir；不合并或覆盖已有目录" }
        require(!source.toPath().startsWith(destination.toPath())) { "解压目录不能包含输入 ZIP" }
        require(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) { "无法创建解压父目录" }
        val temporary = Files.createTempDirectory(destination.parentFile!!.toPath(), ".hanwo-unzip-").toFile()
        try {
            var bytes = 0L
            var files = 0
            var count = 0
            ZipFile(source, Charset.forName(charsetName)).use { archive ->
                require(archive.size() <= maxEntries) { "ZIP 条目数超过 max_entries" }
                val entries = archive.entries()
                val names = hashSetOf<String>()
                var nameChars = 0
                while (entries.hasMoreElements()) {
                    checkTime(deadline)
                    val entry = entries.nextElement()
                    require(++count <= maxEntries) { "ZIP 条目数超过 max_entries" }
                    val name = validateName(entry.name, entry.isDirectory)
                    nameChars += name.length
                    require(nameChars <= 256_000) { "ZIP 文件名总长度过大" }
                    require(names.add(name)) { "ZIP 包含重复路径：$name" }
                    require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) { "不支持此 ZIP 压缩方法（仅支持 Stored/Deflate）" }
                    require(entry.size >= 0 && entry.crc >= 0) { "ZIP 缺少长度或 CRC 信息" }
                    require(entry.size <= maxBytes - bytes) { "ZIP 解压大小超过 max_bytes" }
                    val target = File(temporary, name)
                    require(target.canonicalFile.toPath().startsWith(temporary.canonicalFile.toPath())) { "ZIP 路径越界" }
                    if (entry.isDirectory) {
                        require(entry.size == 0L && entry.crc == 0L) { "ZIP 目录条目包含数据" }
                        require(target.isDirectory || target.mkdirs()) { "ZIP 文件和目录路径冲突" }
                    } else {
                        require(!target.exists()) { "ZIP 文件和目录路径冲突：$name" }
                        require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "ZIP 文件和目录路径冲突" }
                        val crc = CRC32()
                        var fileBytes = 0L
                        archive.getInputStream(entry).use { input ->
                            target.outputStream().buffered().use { output ->
                                copy(input, output, deadline, onBytes = { n ->
                                    fileBytes += n
                                    bytes += n
                                    require(bytes <= maxBytes && fileBytes <= entry.size) { "ZIP 解压大小超过 max_bytes 或声明长度" }
                                }, crc = crc)
                            }
                        }
                        require(fileBytes == entry.size && crc.value == entry.crc) { "ZIP 数据损坏：长度或 CRC 校验失败" }
                        files++
                    }
                }
            }
            checkTime(deadline)
            paths.resolveWithoutSymlinks(outputDir, write = true)
            Files.move(temporary.toPath(), destination.toPath())
            return buildJsonObject {
                put("output_dir", paths.normalize(outputDir))
                put("entries", count)
                put("files", files)
                put("bytes", bytes)
            }
        } finally { temporary.deleteRecursively() }
    }

    private fun output(path: String): File {
        require(paths.normalize(path).startsWith("/workspace/")) { "ZIP 输出必须位于 /workspace 子路径" }
        return paths.resolveWithoutSymlinks(path, write = true)
    }

    private fun validateName(raw: String, directory: Boolean): String {
        require(raw.length in 1..4096 && !raw.startsWith('/') && '\\' !in raw && ':' !in raw && raw.none { it.code < 32 }) { "ZIP 文件名非法或路径越界" }
        val name = if (directory) raw.removeSuffix("/") else raw
        val parts = name.split('/')
        require(parts.size <= 32 && parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "ZIP 路径越界或目录层级超过 32" }
        return name
    }

    private fun deadline(requested: Long, maxBytes: Long): Long {
        require(maxBytes in 1..MAX_BYTES) { "max_bytes 必须在 1..134217728 之间（最多 128 MiB）" }
        return minOf(requested, System.nanoTime() + 120_000_000_000L).also(::checkTime)
    }

    private fun checkTime(deadline: Long) {
        check(System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) { "runtime_timeout: ZIP 操作超时或已取消" }
    }

    private fun copy(input: InputStream, output: OutputStream, deadline: Long, onBytes: (Int) -> Unit, crc: CRC32? = null) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            checkTime(deadline)
            val count = input.read(buffer)
            if (count < 0) break
            onBytes(count)
            crc?.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
    }

    private fun hash(file: File, deadline: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            copy(input, object : OutputStream() { override fun write(b: Int) = Unit; override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len) } }, deadline, {})
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inner class LimitedOutput(output: OutputStream, private val maxBytes: Long, private val deadline: Long) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(b: Int) { reserve(1); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { reserve(len); out.write(b, off, len) }
        private fun reserve(size: Int) {
            checkTime(deadline)
            count += size
            require(count <= maxBytes) { "ZIP 压缩结果超过 max_bytes" }
        }
    }

    companion object {
        private const val MAX_BYTES = 128L * 1024 * 1024
        private const val MAX_ENTRIES = 1000
    }
}
