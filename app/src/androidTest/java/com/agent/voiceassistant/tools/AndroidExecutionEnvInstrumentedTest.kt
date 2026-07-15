package com.agent.voiceassistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidExecutionEnvInstrumentedTest {
    @Test
    fun virtualDirectoriesAndExecutionWorkOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = AndroidExecutionEnv(context)

        val source = env.read("/source")
        assertEquals("directory", source.kind)
        assertTrue(source.content.contains("[dir] app/"))

        env.write("/workspace/instrumentation-check.txt", "ok")
        assertEquals("ok", env.read("/workspace/instrumentation-check.txt").content)

        val command = env.exec("pwd", cwd = "/source", timeoutSeconds = 5)
        assertEquals(0, command.exitCode)
        assertTrue(command.output.endsWith("/agent-runtime/source"))
    }

    @Test
    fun optionalShortHttpResponseWorksOnDevice() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("integration_http_url") ?: return@runBlocking
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val result = AndroidExecutionEnv(context).httpRequest("GET", url)

        assertEquals(200, result.status)
        assertTrue(result.body.isNotBlank())
        assertTrue(!result.truncated)
    }
}
