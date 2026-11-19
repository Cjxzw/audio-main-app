package com.agent.voiceassistant.tools

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.tools.runtime.QuickJsRuntimeExecutor
import com.agent.voiceassistant.tools.runtime.RuntimeCommandRegistry
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ZipArchiveInstrumentedTest {
    private lateinit var directory: File
    private lateinit var env: AndroidExecutionEnv
    private lateinit var commands: RuntimeCommandRegistry

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "zip-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        env = AndroidExecutionEnv(context)
        commands = RuntimeCommandRegistry(env)
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun call(name: String, text: String) = commands.executeJson(name, text).let { Json.parseToJsonElement(it).jsonObject }
    private fun file(name: String) = File(env.workspaceRoot, name)
    private fun failure(block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("Expected rejection", error)
        return error!!.message.orEmpty()
    }
    private fun archive(name: String, entries: List<Pair<String, ByteArray>>, charset: Charset = Charsets.UTF_8, stored: Boolean = false): File = file(name).also { target ->
        ZipOutputStream(target.outputStream(), charset).use { zip ->
            entries.forEach { (path, bytes) ->
                val entry = ZipEntry(path)
                if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().also { it.update(bytes) }.value }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }
    }
    private fun noTemporaryFiles() {
        assertTrue(env.workspaceRoot.listFiles().orEmpty().none { it.name.startsWith(".hanwo-zip-") || it.name.startsWith(".hanwo-unzip-") })
    }

    @Test fun quickJsRoundTripPreservesUnicodeDirectoriesEmptyFilesAndBinary() {
        val root = file("材料🙂").apply { mkdirs() }
        File(root, "子目录/空目录").mkdirs()
        File(root, "子目录/说明.txt").writeText("中文与 emoji🙂\n第二行")
        File(root, "空文件").writeBytes(byteArrayOf())
        val binary = ByteArray(200_003) { ((it * 31) % 256).toByte() }
        File(root, "原始.bin").writeBytes(binary)
        file("单独.txt").writeText("another")
        val runtime = QuickJsRuntimeExecutor(RuntimeCommandRegistry(env, setOf("zip", "unzip")))
        val result = runtime.execute("""function main() {
            const packed=hanwo.zip({paths:['/workspace/材料🙂','/workspace/单独.txt'],output_path:'/workspace/打包🙂.zip'});
            const unpacked=hanwo.call('unzip',{path:packed.output_path,output_dir:'/workspace/解压🙂'});
            return {packed,unpacked};
        }""")
        val value = Json.parseToJsonElement(result.valueJson).jsonObject
        assertEquals(4, value["packed"]!!.jsonObject["files"]!!.jsonPrimitive.int)
        assertEquals(7, value["unpacked"]!!.jsonObject["entries"]!!.jsonPrimitive.int)
        assertArrayEquals(binary, file("解压🙂/材料🙂/原始.bin").readBytes())
        assertEquals("中文与 emoji🙂\n第二行", file("解压🙂/材料🙂/子目录/说明.txt").readText())
        assertTrue(file("解压🙂/材料🙂/子目录/空目录").isDirectory)
        assertEquals(0L, file("解压🙂/材料🙂/空文件").length())
        assertEquals("another", file("解压🙂/单独.txt").readText())
        assertEquals(env.fileSha256("/workspace/打包🙂.zip"), value["packed"]!!.jsonObject["sha256"]!!.jsonPrimitive.content)
        ZipFile(file("打包🙂.zip")).use { zip -> assertArrayEquals(binary, zip.getInputStream(zip.getEntry("材料🙂/原始.bin")).use { it.readBytes() }) }
        noTemporaryFiles()
    }

    @Test fun storedLegacyChineseAndEmptyArchivesAreSupported() {
        archive("legacy.zip", listOf("中文/说明.txt" to "GBK filename".toByteArray()), Charset.forName("GBK"), stored = true)
        call("unzip", """{"path":"/workspace/legacy.zip","output_dir":"/workspace/legacy","filename_charset":"GBK"}""")
        assertEquals("GBK filename", file("legacy/中文/说明.txt").readText())
        archive("empty.zip", emptyList())
        assertEquals(0, call("unzip", """{"path":"/workspace/empty.zip","output_dir":"/workspace/empty"}""")["entries"]!!.jsonPrimitive.int)
        assertTrue(file("empty").isDirectory)
        file("empty-input").mkdirs()
        call("zip", """{"path":"/workspace/empty-input","output_path":"/workspace/empty-dir.zip"}""")
        call("unzip", """{"path":"/workspace/empty-dir.zip","output_dir":"/workspace/empty-out"}""")
        assertTrue(file("empty-out/empty-input").isDirectory)
        failure { call("unzip", """{"path":"/workspace/legacy.zip","output_dir":"/workspace/wrong-charset"}""") }
        assertFalse(file("wrong-charset").exists())
        noTemporaryFiles()
    }

    @Test fun hostPythonArchivesInteroperateAndEncryptedArchivesAreRejected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (name in listOf("host-python.zip", "encrypted.zip")) {
            instrumentation.context.assets.open("zip/$name").use { input -> file(name).outputStream().use { input.copyTo(it) } }
        }
        call("unzip", """{"path":"/workspace/host-python.zip","output_dir":"/workspace/python"}""")
        assertEquals("Python → Android ZIP 互通🙂\n", file("python/主机生成/说明🙂.txt").readText())
        assertArrayEquals(ByteArray(1024) { (it % 256).toByte() }, file("python/主机生成/原始.bin").readBytes())
        assertTrue(file("python/主机生成/空目录").isDirectory)
        call("zip", """{"path":"/workspace/python/主机生成","output_path":"/workspace/android-produced.zip"}""")
        // Optional export lets the local harness independently read this archive with Python.
        if (InstrumentationRegistry.getArguments().getString("zip_interop_export") == "true") {
            file("android-produced.zip").copyTo(File(instrumentation.targetContext.cacheDir, "zip-interop-android.zip"), overwrite = true)
        }
        failure { call("unzip", """{"path":"/workspace/encrypted.zip","output_dir":"/workspace/encrypted"}""") }
        assertFalse(file("encrypted").exists())
        noTemporaryFiles()
    }

    @Test fun existingFilesAndDirectoriesArePreservedAndFileOverwriteIsExplicit() {
        file("input.txt").writeText("new content")
        file("out.zip").writeText("old archive")
        failure { call("zip", """{"path":"/workspace/input.txt","output_path":"/workspace/out.zip"}""") }
        assertEquals("old archive", file("out.zip").readText())
        call("zip", """{"path":"/workspace/input.txt","output_path":"/workspace/out.zip","overwrite":true}""")
        file("out").mkdirs(); file("out/keep.txt").writeText("keep")
        failure { call("unzip", """{"path":"/workspace/out.zip","output_dir":"/workspace/out"}""") }
        failure { call("unzip", """{"path":"/workspace/out.zip","output_dir":"/workspace/out","overwrite":true}""") }
        assertEquals(listOf("keep.txt"), file("out").list()!!.toList())
        assertEquals("keep", file("out/keep.txt").readText())
        noTemporaryFiles()
    }

    @Test fun pathsPermissionsLinksAndAmbiguousArgumentsAreRejected() {
        file("input").mkdirs(); file("input/a").writeText("x")
        file("other").mkdirs(); file("other/a").writeText("y")
        failure { call("zip", """{"paths":["/workspace/input/a","/workspace/other/a"],"output_path":"/workspace/collision.zip"}""") }
        failure { call("zip", """{"path":"/workspace/input","output_path":"/workspace/input/recursive.zip"}""") }
        failure { call("zip", """{"path":"/workspace/input/a","output_path":"/workspace/input/a","overwrite":true}""") }
        failure { call("zip", """{"path":"/workspace/input","output_path":"/source/a.zip"}""") }
        failure { call("zip", """{"path":"/workspace/../input","output_path":"/workspace/no.zip"}""") }
        failure { call("zip", """{"path":"/workspace/input","paths":[],"output_path":"/workspace/no.zip"}""") }
        failure { call("zip", """{"paths":[null],"output_path":"/workspace/no.zip"}""") }
        failure { call("zip", """{"path":"/workspace/input","output_path":"/workspace/no.zip","overwrite":"yes"}""") }
        Files.createSymbolicLink(file("alias").toPath(), file("input").toPath())
        failure { call("zip", """{"path":"/workspace/alias/a","output_path":"/workspace/no.zip"}""") }
        Files.createSymbolicLink(file("input/link").toPath(), file("other/a").toPath())
        failure { call("zip", """{"path":"/workspace/input","output_path":"/workspace/no.zip"}""") }
        val limited = QuickJsRuntimeExecutor(RuntimeCommandRegistry(env, setOf("read")))
        assertEquals("[\"undefined\",\"undefined\"]", limited.execute("function main(){return [typeof hanwo.zip,typeof hanwo.unzip];}").valueJson)
        assertTrue(failure { limited.execute("function main(){return hanwo.call('zip',{});}") }.contains("permission_denied command=zip"))
        assertFalse("zip" in RuntimeCommandRegistry.RUN_COMMANDS)
        assertFalse("unzip" in RuntimeCommandRegistry.RUN_COMMANDS)
        noTemporaryFiles()
    }

    @Test fun traversalAndFileDirectoryCollisionsDoNotPublishPartialOutput() {
        val badNames = listOf("../escape", "/absolute", "C:/escape", "a\\..\\escape", "a/../escape", "a//b", "./x")
        badNames.forEachIndexed { i, bad ->
            archive("bad.zip", listOf("valid.txt" to "ok".toByteArray(), bad to "bad".toByteArray()))
            failure { call("unzip", """{"path":"/workspace/bad.zip","output_dir":"/workspace/bad-$i"}""") }
            assertFalse(file("bad-$i").exists())
        }
        for (entries in listOf(listOf("a" to byteArrayOf(1), "a/b" to byteArrayOf(2)), listOf("a/b" to byteArrayOf(1), "a" to byteArrayOf(2)))) {
            archive("conflict.zip", entries)
            failure { call("unzip", """{"path":"/workspace/conflict.zip","output_dir":"/workspace/conflict"}""") }
            assertFalse(file("conflict").exists())
        }
        assertFalse(File(env.workspaceRoot.parentFile, "escape").exists())
        noTemporaryFiles()
    }

    @Test fun corruptCrcTruncatedAndNonZipInputsFailCleanly() {
        val good = archive("good.zip", listOf("data.bin" to "unique-content".toByteArray()), stored = true).readBytes()
        val dataStart = 30 + ((good[26].toInt() and 255) or ((good[27].toInt() and 255) shl 8)) + ((good[28].toInt() and 255) or ((good[29].toInt() and 255) shl 8))
        val crcBad = good.copyOf().also { it[dataStart] = (it[dataStart].toInt() xor 1).toByte() }
        listOf(crcBad, good.copyOf(good.size - 22), "not zip".toByteArray()).forEach { bytes ->
            file("bad.zip").writeBytes(bytes)
            failure { call("unzip", """{"path":"/workspace/bad.zip","output_dir":"/workspace/bad"}""") }
            assertFalse(file("bad").exists())
        }
        // The central directory is authoritative; duplicate normalized paths must still be rejected.
        val duplicates = archive("duplicate.zip", listOf("a" to byteArrayOf(1), "b" to byteArrayOf(2)), stored = true).readBytes()
        for (i in 0 until duplicates.size - 46) {
            if (duplicates[i] == 0x50.toByte() && duplicates[i+1] == 0x4b.toByte() && duplicates[i+2] == 1.toByte() && duplicates[i+3] == 2.toByte() && duplicates[i+46] == 'b'.code.toByte()) duplicates[i+46] = 'a'.code.toByte()
        }
        file("duplicate.zip").writeBytes(duplicates)
        failure { call("unzip", """{"path":"/workspace/duplicate.zip","output_dir":"/workspace/duplicate"}""") }
        assertFalse(file("duplicate").exists())
        noTemporaryFiles()
    }

    @Test fun byteEntryAndDeadlineLimitsKeepOldArchiveAndCleanStaging() {
        file("large.bin").writeBytes(ByteArray(1_000_000) { (it % 127).toByte() })
        file("out.zip").writeText("old")
        failure { call("zip", """{"path":"/workspace/large.bin","output_path":"/workspace/out.zip","max_bytes":80,"overwrite":true}""") }
        assertEquals("old", file("out.zip").readText())
        archive("bomb.zip", listOf("first" to byteArrayOf(1), "large" to ByteArray(1_000_000)))
        assertTrue(failure { call("unzip", """{"path":"/workspace/bomb.zip","output_dir":"/workspace/bomb","max_bytes":100}""") }.contains("max_bytes"))
        failure { call("unzip", """{"path":"/workspace/bomb.zip","output_dir":"/workspace/bomb","max_entries":1}""") }
        assertFalse(file("bomb").exists())
        failure { env.zip(listOf("/workspace/large.bin"), "/workspace/out.zip", 8388608, true, System.nanoTime() - 1) }
        failure { env.unzip("/workspace/bomb.zip", "/workspace/bomb", 8388608, 1000, "UTF-8", System.nanoTime() - 1) }
        Thread.currentThread().interrupt()
        try { assertTrue(failure { env.zip(listOf("/workspace/large.bin"), "/workspace/out.zip", 8388608, true, Long.MAX_VALUE) }.contains("runtime_timeout")) }
        finally { Thread.interrupted() }
        assertEquals("old", file("out.zip").readText())
        noTemporaryFiles()
    }

    @Test fun cancellingAnActiveZipStopsStreamingAndPreservesOldTarget() {
        val random = java.util.Random(42)
        val buffer = ByteArray(65536)
        file("random.bin").outputStream().use { output -> repeat(512) { random.nextBytes(buffer); output.write(buffer) } }
        file("cancel.zip").writeText("old")
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = Thread {
            try { env.zip(listOf("/workspace/random.bin"), "/workspace/cancel.zip", 64L * 1024 * 1024, true, Long.MAX_VALUE) }
            catch (failure: Throwable) { error.set(failure) }
        }
        worker.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (worker.isAlive && env.workspaceRoot.listFiles().orEmpty().none { it.name.startsWith(".hanwo-zip-") } && System.nanoTime() < deadline) Thread.sleep(5)
        worker.interrupt()
        worker.join(3000)
        assertFalse("ZIP cancellation did not stop within 3 seconds", worker.isAlive)
        assertNotNull("Expected cancellation during streaming", error.get())
        assertTrue(error.get().toString(), error.get().message.orEmpty().contains("runtime_timeout"))
        assertEquals("old", file("cancel.zip").readText())
        noTemporaryFiles()
    }
}
