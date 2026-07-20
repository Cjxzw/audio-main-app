package com.agent.voiceassistant.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReplyDirectiveTest {
    @Test
    fun `parses preset singing reply`() {
        val directive = VoiceReplyDirectiveParser.parse(
            """{"text":"祝你生日快乐","mode":"preset","voice":"茉莉","performance":"singing"}""",
        )

        assertEquals("祝你生日快乐", directive.text)
        assertEquals(VoiceReplyMode.PRESET, directive.options.mode)
        assertEquals("茉莉", directive.options.voice)
        assertEquals(VoicePerformance.SINGING, directive.options.performance)
    }

    @Test
    fun `design mode requires voice prompt and rejects singing`() {
        val missingPrompt = runCatching {
            VoiceReplyDirectiveParser.parse("""{"text":"你好","mode":"design"}""")
        }
        val singing = runCatching {
            VoiceReplyDirectiveParser.parse(
                """{"text":"你好","mode":"design","voice_prompt":"清亮青年声","performance":"singing"}""",
            )
        }

        assertTrue(missingPrompt.isFailure)
        assertTrue(singing.isFailure)
    }

    @Test
    fun `tts payload keeps singing marker internal and selects voice`() {
        val client = CloudSpeechClient(testConfig())
        val payload = client.buildTtsPayload(
            text = "测试正文",
            stream = true,
            options = VoiceReplyOptions(
                voice = "苏打",
                performance = VoicePerformance.SINGING,
            ),
        ).toString()

        assertTrue(payload.contains("mimo-v2.5-tts"))
        assertTrue(payload.contains("(唱歌)测试正文"))
        assertTrue(payload.contains("苏打"))
        client.shutdown()
    }

    @Test
    fun `voice design payload places optimization flag inside audio and omits voice`() {
        val client = CloudSpeechClient(testConfig())
        val payload = client.buildTtsPayload(
            text = "测试正文",
            stream = false,
            options = VoiceReplyOptions(
                mode = VoiceReplyMode.DESIGN,
                voicePrompt = "年轻、清亮、自然的中文女声，语速略快。",
            ),
        )
        val audio = payload.getValue("audio").let { it as kotlinx.serialization.json.JsonObject }

        assertTrue(payload.toString().contains("mimo-v2.5-tts-voicedesign"))
        assertEquals(false, audio.getValue("optimize_text_preview").toString().toBoolean())
        assertTrue(!audio.containsKey("voice"))
        assertTrue(!payload.containsKey("optimize_text_preview"))
        client.shutdown()
    }

    private fun testConfig() = com.agent.voiceassistant.agent.LLMConfig(
        apiKey = "test",
        baseUrl = "https://example.com/v1",
        modelName = "test-model",
    )
}
