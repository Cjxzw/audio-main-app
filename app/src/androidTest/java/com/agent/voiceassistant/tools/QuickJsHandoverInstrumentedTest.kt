package com.agent.voiceassistant.tools

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.agent.AgentAction
import com.agent.voiceassistant.agent.runtime.SkillApprovalStore
import com.agent.voiceassistant.agent.runtime.SkillRegistry
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.tools.runtime.QuickJsRuntimeExecutor
import com.agent.voiceassistant.tools.runtime.RuntimeCommandRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class QuickJsHandoverInstrumentedTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var env: AndroidExecutionEnv
    private lateinit var commands: RuntimeCommandRegistry

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "qjs-review-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        env = AndroidExecutionEnv(context)
        commands = RuntimeCommandRegistry(env)
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun args(vararg values: Pair<String, Any>) = buildJsonObject {
        values.forEach { (key, value) -> when (value) {
            is String -> put(key, value)
            is Boolean -> put(key, value)
            is Number -> put(key, value)
            else -> error("Unsupported test argument")
        } }
    }
    private fun call(name: String, vararg values: Pair<String, Any>) = commands.execute(name, args(*values)).value.jsonObject
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun failure(block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("Expected rejection", error)
        return error!!.message.orEmpty()
    }

    @Test fun binaryReadWriteUsesRawHashAndStrictCodecs() {
        val bytes = ByteArray(64) { (it * 37 + 128).toByte() }
        File(env.workspaceRoot, "raw.bin").writeBytes(bytes)
        val digest = hash(bytes)
        assertTrue(failure { call("read", "path" to "/workspace/raw.bin") }.contains("UTF-8"))
        for (encoding in listOf("base64", "hex")) {
            val read = call("read", "path" to "/workspace/raw.bin", "encoding" to encoding)
            assertEquals(digest, read["sha256"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, read["start_line"])
            assertEquals("64", read["total_bytes"]!!.jsonPrimitive.content)
            val write = call("write", "path" to "/workspace/$encoding.bin", "content" to read["content"]!!.jsonPrimitive.content, "encoding" to encoding)
            assertEquals(digest, write["sha256"]!!.jsonPrimitive.content)
            assertEquals("64", write["bytes_written"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, File(env.workspaceRoot, "$encoding.bin").readBytes())
            failure { call("write", "path" to "/workspace/$encoding.bin", "content" to "", "encoding" to encoding, "mode" to "patch") }
        }
        failure { call("write", "path" to "/workspace/raw.bin", "content" to "zz", "encoding" to "hex") }
        failure { call("write", "path" to "/workspace/raw.bin", "content" to "YQ==", "encoding" to "base64", "expected_sha256" to "wrong") }
        failure { call("read", "path" to "/workspace/raw.bin", "encoding" to "base64", "limit" to 20) }
        failure { call("read", "path" to "/workspace/raw.bin", "encoding" to "base64", "byte_offset" to -1) }
        failure { call("read", "path" to "/workspace/raw.bin", "encoding" to "hex", "byte_limit" to 30000) }
        failure { call("write", "path" to "/source/nope", "content" to "YQ==", "encoding" to "base64") }
        failure { call("write", "path" to "/workspace/../nope", "content" to "YQ==", "encoding" to "base64") }
        failure { call("write", "path" to "/workspace/token", "encoding" to "base64", "content" to java.util.Base64.getEncoder().encodeToString("{{cred:a.b}}".toByteArray())) }
        assertArrayEquals(bytes, File(env.workspaceRoot, "raw.bin").readBytes())
    }

    @Test fun binaryPaginationAndLargeFilesPreserveBytes() {
        val bytes = ByteArray(100000) { (it % 256).toByte() }
        File(env.workspaceRoot, "pages.bin").writeBytes(bytes)
        for (encoding in listOf("base64", "hex")) {
            val result = ByteArrayOutputStream()
            var offset = 0L
            do {
                val page = call("read", "path" to "/workspace/pages.bin", "encoding" to encoding, "byte_offset" to offset, "byte_limit" to 7001)
                result.write(BinaryFileCodec.decode(page["content"]!!.jsonPrimitive.content, encoding))
                assertTrue(page.toString().length < 40000)
                offset = page["next_byte_offset"]!!.jsonPrimitive.long
                val more = page["has_more"]!!.jsonPrimitive.boolean
            } while (more)
            assertEquals(bytes.size.toLong(), offset)
            assertArrayEquals(bytes, result.toByteArray())
        }
        val big = ByteArray(2 * 1024 * 1024 + 31) { (it % 256).toByte() }
        File(env.workspaceRoot, "large.bin").writeBytes(big)
        assertEquals(hash(big), call("read", "path" to "/workspace/large.bin", "encoding" to "base64", "byte_limit" to 1)["sha256"]!!.jsonPrimitive.content)
        assertEquals(hash(big), call("sha256", "path" to "/workspace/large.bin")["sha256"]!!.jsonPrimitive.content)
        failure { call("sha256", "path" to "/workspace/large.bin", "value" to "x") }
        java.io.RandomAccessFile(File(env.workspaceRoot, "too-large.bin"), "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        failure { call("read", "path" to "/workspace/too-large.bin", "encoding" to "base64") }
    }

    @Test fun textPatchAndSharedTopLevelResultsRemainCompatible() = runBlocking {
        val executor = LocalToolExecutor(ConversationStore(context), executionEnv = env)
        val payload = args("path" to "/workspace/text.txt", "content" to "中文🙂\nsecond\n", "mode" to "create")
        val tool = executor.execute(AgentAction("write", payload, payload.toString()))
        assertTrue(tool.contextText, tool.success)
        assertTrue(Json.parseToJsonElement(tool.contextText).jsonObject.containsKey("bytes_written"))
        val read = executor.execute(AgentAction("read", args("path" to "/workspace/text.txt"), "{}"))
        val readValue = Json.parseToJsonElement(read.contextText).jsonObject
        assertEquals(call("read", "path" to "/workspace/text.txt"), readValue)
        call("write", "path" to "/workspace/text.txt", "content" to "替换🙂", "mode" to "patch", "start_line" to 2, "end_line" to 2, "expected_sha256" to readValue["sha256"]!!.jsonPrimitive.content)
        assertTrue(File(env.workspaceRoot, "text.txt").readText().contains("替换🙂"))
        failure { call("write", "path" to "/workspace/text.txt", "content" to "stale", "mode" to "patch", "start_line" to 1, "end_line" to 1, "expected_sha256" to readValue["sha256"]!!.jsonPrimitive.content) }
        val runtime = QuickJsRuntimeExecutor(commands)
        val result = runtime.execute("function main(input) { hanwo.emit('中文🙂\\u0000tail'); const r=hanwo.read(input); return {text:r.content, hash:r.sha256, echo:input.emoji}; }", args("path" to "/workspace/text.txt", "emoji" to "🙂中文").toString())
        val value = Json.parseToJsonElement(result.valueJson).jsonObject
        assertEquals("🙂中文", value["echo"]!!.jsonPrimitive.content)
        assertEquals("中文🙂\u0000tail", result.output)
        assertTrue(value["text"]!!.jsonPrimitive.content.contains("替换🙂"))
    }

    @Test fun permissionsAndErrorsAreConsistentForNamedAndDynamicCalls() {
        val limited = QuickJsRuntimeExecutor(RuntimeCommandRegistry(env, allowedCommands = setOf("read")))
        val visibility = Json.parseToJsonElement(limited.execute("function main() { return {read:typeof hanwo.read,write:typeof hanwo.write,gunzip:typeof hanwo.gunzip}; }").valueJson).jsonObject
        assertEquals("undefined", visibility["write"]!!.jsonPrimitive.content)
        assertEquals("undefined", visibility["gunzip"]!!.jsonPrimitive.content)
        assertTrue(failure { limited.execute("function main() { return hanwo.call('write',{path:'/workspace/nope',content:'x'}); }") }.contains("permission_denied command=write"))
        assertFalse(File(env.workspaceRoot, "nope").exists())
        val empty = QuickJsRuntimeExecutor(RuntimeCommandRegistry(env, allowedCommands = emptySet()))
        assertEquals("[]", empty.execute("function main() { return Object.keys(hanwo).filter(k=>k!=='call'&&k!=='emit'); }").valueJson)
        val error = failure { QuickJsRuntimeExecutor(commands).execute("function main() { return hanwo.write({path:'/source/../escape',content:'x'}); }") }
        assertTrue(error, error.contains("command=write") && error.contains("路径不能包含 ..") && error.contains("keys=content,path"))
        assertTrue(failure { QuickJsRuntimeExecutor(commands).execute("async function main() { return 1; }") }.contains("Promise"))
    }

    @Test fun compressionRoundTripsUseNativeZlibAndSharedDispatch() {
        val original = "中文🙂 native compression\n".repeat(200).toByteArray()
        File(env.workspaceRoot, "input.txt").writeBytes(original)
        val runtime = QuickJsRuntimeExecutor(commands)
        for ((pack, unpack) in listOf("gzip" to "gunzip", "deflate" to "inflate")) {
            val script = """function main() {
                const p=hanwo.$pack({path:'/workspace/input.txt',output_path:'/workspace/packed.$pack'});
                const r=hanwo.call('$unpack',{path:'/workspace/packed.$pack',output_path:'/workspace/unpacked.$pack',preview_chars:4});
                return {packed:p,unpacked:r,hash:hanwo.sha256({path:r.output_path}).sha256};
            }"""
            val result = Json.parseToJsonElement(runtime.execute(script).valueJson).jsonObject
            assertEquals(hash(original), result["hash"]!!.jsonPrimitive.content)
            assertArrayEquals(original, File(env.workspaceRoot, "unpacked.$pack").readBytes())
            assertTrue(result["unpacked"]!!.jsonObject["has_more"]!!.jsonPrimitive.boolean)
            if (pack == "gzip") assertArrayEquals(original, GZIPInputStream(File(env.workspaceRoot, "packed.gzip").inputStream()).use { it.readBytes() })
        }
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(original) } }.toByteArray()
        val result = call("gunzip", "base64" to java.util.Base64.getEncoder().encodeToString(zipped))
        assertArrayEquals(original, BinaryFileCodec.decode(result["base64"]!!.jsonPrimitive.content, "base64"))
    }

    @Test fun compressionFailurePreservesTargetsAndDeletesTemporaryFiles() {
        val original = ByteArray(100000) { 65 }
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(original) } }.toByteArray()
        File(env.workspaceRoot, "bomb.gz").writeBytes(zipped)
        File(env.workspaceRoot, "target").writeText("keep")
        assertTrue(failure { call("gunzip", "path" to "/workspace/bomb.gz", "output_path" to "/workspace/target", "overwrite" to true, "max_bytes" to 1000) }.contains("output_limit_exceeded"))
        assertEquals("keep", File(env.workspaceRoot, "target").readText())
        failure { call("gunzip", "path" to "/workspace/bomb.gz", "output_path" to "/workspace/bomb.gz", "overwrite" to true) }
        failure { call("gunzip", "path" to "/workspace/bomb.gz", "output_path" to "/source/nope") }
        failure { call("gunzip", "path" to "/workspace/bomb.gz", "output_path" to "/workspace/target") }
        failure { call("gunzip", "path" to "/workspace/bomb.gz") } // inline result cannot exceed context budget
        val badCrc = zipped.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 128).toByte() }
        File(env.workspaceRoot, "crc.gz").writeBytes(badCrc)
        failure { call("gunzip", "path" to "/workspace/crc.gz", "output_path" to "/workspace/target", "overwrite" to true) }
        File(env.workspaceRoot, "multiple.gz").writeBytes(zipped + zipped)
        assertTrue(failure { call("gunzip", "path" to "/workspace/multiple.gz", "output_path" to "/workspace/target", "overwrite" to true) }.contains("multiple_members"))
        File(env.workspaceRoot, "broken.gz").writeBytes(zipped.copyOf(zipped.size - 4))
        val corrupt = failure { call("gunzip", "path" to "/workspace/broken.gz", "output_path" to "/workspace/target", "overwrite" to true) }
        assertTrue(corrupt, corrupt.contains("gunzip:") && corrupt.contains("zlib="))
        assertEquals("keep", File(env.workspaceRoot, "target").readText())
        assertFalse(env.workspaceRoot.listFiles()!!.any { it.name.startsWith(".hanwo-compress-") })
        val timeout = failure {
            // Exercise native deadline enforcement directly: JS interrupts cannot stop a C loop.
            val input = android.os.ParcelFileDescriptor.open(File(env.workspaceRoot, "bomb.gz"), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val output = android.os.ParcelFileDescriptor.open(File(env.workspaceRoot, "deadline.tmp"), android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE)
            input.use { source -> output.use { target -> com.agent.voiceassistant.tools.runtime.NativeCompression.transform(source.fd, target.fd, "gunzip", 8L * 1024 * 1024, System.nanoTime() - 1) } }
        }
        assertTrue(timeout, timeout.contains("runtime_timeout"))
    }

    @Test fun logFiltersBatchBudgetAndLargeTextHashesRemainStable() {
        File(env.logsRoot, "review.log").writeText("12:00:00.000 I/VA_AGENT: agent.start hello\n12:00:01.000 E/VA_TOOL: tool.failed sample\n")
        val log = commands.execute("read", buildJsonObject {
            put("path", "/logs/review.log"); put("log_levels", buildJsonArray { add("E") })
        }).value.jsonObject
        assertTrue(log["content"]!!.jsonPrimitive.content.contains("tool.failed"))
        assertFalse(log["content"]!!.jsonPrimitive.content.contains("agent.start"))
        failure { commands.execute("read", buildJsonObject { put("path", "/workspace/a"); put("log_levels", buildJsonArray { add("E") }) }) }
        val large = "line 中文🙂\n".repeat(160000).toByteArray()
        File(env.workspaceRoot, "large.txt").writeBytes(large)
        assertEquals(hash(large), call("read", "path" to "/workspace/large.txt", "limit" to 2)["sha256"]!!.jsonPrimitive.content)
        File(env.workspaceRoot, "escaped.txt").writeText("\"".repeat(40000))
        val escaped = call("read", "path" to "/workspace/escaped.txt")
        assertTrue(escaped.toString().length <= 40000)
        assertTrue(escaped["truncated"]!!.jsonPrimitive.boolean)
        val batch = commands.execute("read", buildJsonObject {
            put("paths", buildJsonArray { add("/workspace/escaped.txt"); add("/workspace/large.txt"); add("/workspace/missing") })
        }).value.jsonObject
        assertTrue(batch.toString().length < 10000)
        assertFalse(batch["items"]!!.jsonArray.last().jsonObject["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun concurrentRuntimesAndNativeCancellationRemainIndependent() {
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { n -> workers.submit<String> {
                QuickJsRuntimeExecutor(commands).execute("function main(i) { return hanwo.sha256({value:i}); }", "\"$n\"").valueJson
            } }
            futures.forEachIndexed { index, future ->
                assertEquals(hash((index + 1).toString().toByteArray()), Json.parseToJsonElement(future.get(10, java.util.concurrent.TimeUnit.SECONDS)).jsonObject["sha256"]!!.jsonPrimitive.content)
            }
            val source = File(env.workspaceRoot, "cancel.gz")
            source.outputStream().use { out -> GZIPOutputStream(out).use { it.write("test".toByteArray()) } }
            val cancelled = workers.submit<String> {
                Thread.currentThread().interrupt()
                try { failure { call("gunzip", "path" to "/workspace/cancel.gz", "output_path" to "/workspace/cancelled.txt") } }
                finally { Thread.interrupted() }
            }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(cancelled, cancelled.contains("runtime_cancelled"))
            assertFalse(File(env.workspaceRoot, "cancelled.txt").exists())
        } finally { workers.shutdownNow() }
    }

    @Test fun cancellingSkillCoroutineInterruptsTheNativeRuntime() = runBlocking {
        val candidate = File(env.workspaceRoot, "cancellable").apply { mkdirs() }
        File(candidate, "SKILL.md").writeText("---\nname: cancellable\ndescription: test\nruntime: quickjs\npermissions: []\n---\nTest")
        File(candidate, "main.js").writeText("function main() { while (true) {} }")
        val registry = SkillRegistry(env.skillsRoot, env.disabledSkillsRoot)
        registry.registerFromWorkspace(env.workspaceRoot, "/workspace/cancellable", "cancellable", "test", "SKILL.md", "test", listOf("SKILL.md", "main.js"))
        val executor = LocalToolExecutor(ConversationStore(context), executionEnv = env, skillRegistry = registry)
        val start = System.nanoTime()
        var cancelled = false
        try {
            kotlinx.coroutines.withTimeout(300) {
                executor.execute(AgentAction("skill.use", args("skill_name" to "cancellable", "mode" to "run"), "{}"))
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue("Cancellation must not wait for the default 30 second runtime timeout", (System.nanoTime() - start) / 1_000_000 < 3000)
    }

    @Test fun approvalAndCredentialRegressionSurviveRestart() {
        val vault = CredentialVault(context)
        val sentence = "这是节省 token 的设计"
        assertEquals(sentence, vault.capture(sentence, "test").text)
        assertTrue(vault.records().isEmpty())
        val captured = vault.capture("密码是 中文短值", "test")
        assertEquals(1, captured.references.size)
        assertEquals("中文短值", CredentialVault(context).lookup(captured.references.single())!!.second.value)
        val registry = SkillRegistry(env.skillsRoot, env.disabledSkillsRoot)
        val source = File(env.workspaceRoot, "empty-skill").apply { mkdirs() }
        File(source, "SKILL.md").writeText("---\nname: empty test\ndescription: test\nruntime: quickjs\npermissions: []\n---\nTest")
        File(source, "main.js").writeText("function main() { return typeof hanwo.write; }")
        val root = File(directory, "approvals")
        val store = SkillApprovalStore(root, env.workspaceRoot, registry)
        val request = store.propose("skill.register", buildJsonObject {
            put("source_path", "/workspace/empty-skill"); put("name", "empty test"); put("description", "test")
            put("reviewed_files", buildJsonArray { add("SKILL.md"); add("main.js") })
        }, "test")
        assertTrue(request.permissionsDeclared)
        assertTrue(request.summary.contains("无命令权限"))
        assertNull(registry.findByName("empty test"))
        SkillApprovalStore(root, env.workspaceRoot, registry).decide(request.id, "approved")
        val installed = SkillRegistry(env.skillsRoot, env.disabledSkillsRoot).findByName("empty test")!!
        assertTrue(installed.permissionsDeclared && installed.permissions.isEmpty())
        assertEquals("\"undefined\"", QuickJsRuntimeExecutor(RuntimeCommandRegistry(env, installed.permissions)).execute(File(env.skillsRoot, "${installed.id}/main.js").readText()).valueJson)
    }
}
