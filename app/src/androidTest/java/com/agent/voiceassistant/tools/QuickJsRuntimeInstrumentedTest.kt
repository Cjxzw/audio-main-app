package com.agent.voiceassistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.data.ConversationStore
import com.agent.voiceassistant.tools.runtime.QuickJsRuntimeExecutor
import com.agent.voiceassistant.tools.runtime.RuntimeCommandRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuickJsRuntimeInstrumentedTest {
    @Test
    fun quickJsCallsHostCommandsAndReturnsJson() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = AndroidExecutionEnv(context)
        val path = "/workspace/qjs-" + UUID.randomUUID() + ".txt"
        val runtime = QuickJsRuntimeExecutor(RuntimeCommandRegistry(env))
        try {
            val result = runtime.execute(
                source = """
                    function main(input) {
                        hanwo.emit("started");
                        const digest = hanwo.call("sha256", {value: input.value});
                        hanwo.write({path: input.path, content: digest.sha256});
                        const file = hanwo.read({path: input.path});
                        return {hash: digest.sha256, content: file.content};
                    }
                """.trimIndent(),
                inputJson = "{\"value\":\"hello\",\"path\":\"$path\"}",
            )
            val value = Json.parseToJsonElement(result.valueJson).jsonObject
            assertEquals(value["hash"]?.jsonPrimitive?.content, value["content"]?.jsonPrimitive?.content)
            assertEquals("started", result.output)
        } finally {
            env.trashWorkspacePath(path)
        }
    }

    @Test
    fun runAndNamedSkillCommandsShareArgumentsAndResults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = AndroidExecutionEnv(context)
        val registry = MainToolRegistry(LocalToolExecutor(ConversationStore(context), executionEnv = env))
        val path = "/workspace/qjs-commands-${UUID.randomUUID()}.txt"
        val args = buildJsonObject {
            put("path", path)
            put("pattern", "warn.")
            put("ignore_case", true)
        }
        try {
            env.write(path, "ok\nWARN. sample\nWARNX not-a-match\n")
            val toolNames = registry.definitions(allowReasoningEscalation = false).map { it.name }.toSet()
            val retired = setOf("exec", "agent_sleep", "voice_reply", "sing_song", "workspace_delete")
            assertTrue(retired.none { it in toolNames || registry.isNativeTool(it) })
            val audited = setOf("read", "write", "delete", "http_request", "describe_credentials")
            assertTrue(toolNames.containsAll(audited))
            for (command in audited) {
                val blocked = registry.execute(CloudSpeechClient.ToolCall("blocked", "run", """{"command":"$command"}"""))
                assertFalse(blocked.result.success)
            }
            val request = buildJsonObject { put("command", "grep"); put("args", args) }
            val run = registry.execute(CloudSpeechClient.ToolCall("grep", "run", request.toString()))
            assertTrue(run.result.contextText, run.result.success)
            val script = QuickJsRuntimeExecutor(RuntimeCommandRegistry(env)).execute(
                source = """function main(input) {
                    return {grep: hanwo.grep(input), stat: hanwo.stat({path: input.path}), wc: hanwo.wc({path: input.path})};
                }""",
                inputJson = args.toString(),
            )
            val value = Json.parseToJsonElement(script.valueJson).jsonObject
            val runValue = Json.parseToJsonElement(run.result.contextText.substringAfter("\n结果："))
            assertEquals(runValue, value.getValue("grep"))
            val match = value.getValue("grep").jsonObject.getValue("matches").jsonArray.single().jsonObject
            assertEquals("2", match.getValue("line").jsonPrimitive.content)
            assertEquals("WARN. sample", match.getValue("text").jsonPrimitive.content)
            assertEquals("3", value.getValue("wc").jsonObject.getValue("lines").jsonPrimitive.content)
            assertEquals("file", value.getValue("stat").jsonObject.getValue("kind").jsonPrimitive.content)
            val denied = runCatching {
                QuickJsRuntimeExecutor(RuntimeCommandRegistry(env, allowedCommands = setOf("read")))
                    .execute("function main(input) { return hanwo.grep(input); }", args.toString())
            }
            assertTrue(denied.isFailure)
        } finally {
            env.trashWorkspacePath(path)
        }
    }

    @Test
    fun quickJsTimeoutInterruptsInfiniteLoop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = QuickJsRuntimeExecutor(RuntimeCommandRegistry(AndroidExecutionEnv(context)))
        val error = runCatching {
            runtime.execute(
                source = "function main(input) { while (true) {} }",
                timeoutMs = 1_000,
            )
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("interrupted", ignoreCase = true))
    }
}
