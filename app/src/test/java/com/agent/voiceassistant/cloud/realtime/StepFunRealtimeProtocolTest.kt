package com.agent.voiceassistant.cloud.realtime

import com.agent.voiceassistant.cloud.CloudSpeechClient
import com.agent.voiceassistant.settings.StepFunRealtimeConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepFunRealtimeProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `builds StepFun session with PCM VAD and function wrappers`() {
        val event = StepFunRealtimeProtocol.sessionUpdate(
            config = StepFunRealtimeConfig(voice = "wenrounansheng", vadSilenceDurationMs = 600),
            instructions = "你是喊我。",
            tools = listOf(
                CloudSpeechClient.ToolDefinition(
                    name = "weather_get_current",
                    description = "查询天气",
                    parameters = json.parseToJsonElement("""{"type":"object"}""").jsonObject,
                ),
            ),
        )

        val session = event["session"]!!.jsonObject
        assertEquals("session.update", event["type"]!!.jsonPrimitive.content)
        assertEquals("pcm16", session["input_audio_format"]!!.jsonPrimitive.content)
        assertEquals("server_vad", session["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val tool = session["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("weather_get_current", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encodes startup context with an accepted conversation role`() {
        val event = StepFunRealtimeProtocol.createContextMessage("主会话快照")
        assertEquals("assistant", event["item"]!!.jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses text audio reasoning and a finished function call`() {
        assertEquals(
            StepFunRealtimeProtocol.Event.TextDelta("item_1", "你好"),
            StepFunRealtimeProtocol.parse("""{"type":"response.text.delta","item_id":"item_1","delta":"你好"}"""),
        )
        assertEquals(
            StepFunRealtimeProtocol.Event.ThinkingDelta("item_1", "分析中"),
            StepFunRealtimeProtocol.parse("""{"type":"response.thinking.delta","item_id":"item_1","delta":"分析中"}"""),
        )
        val function = StepFunRealtimeProtocol.parse(
            """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"weather_get_current","arguments":"{\"location\":\"北京\"}"}""",
        )
        assertTrue(function is StepFunRealtimeProtocol.Event.FunctionArgumentsDone)
        function as StepFunRealtimeProtocol.Event.FunctionArgumentsDone
        assertEquals("call_1", function.callId)
        assertEquals("weather_get_current", function.name)
        assertEquals("{\"location\":\"北京\"}", function.arguments)
        assertEquals(
            StepFunRealtimeProtocol.Event.AudioDone,
            StepFunRealtimeProtocol.parse("""{"type":"response.audio.done"}"""),
        )
    }

    @Test
    fun `reads a completed user transcript from conversation item fallback`() {
        val event = StepFunRealtimeProtocol.parse(
            """{"type":"conversation.item.created","item":{"id":"item_user","role":"user","content":[{"type":"input_audio","transcript":"帮我查天气"}]}}""",
        )

        assertEquals(
            StepFunRealtimeProtocol.Event.UserTranscriptCandidate("item_user", "帮我查天气"),
            event,
        )
    }

    @Test
    fun `does not treat a text item echo as a second user transcript`() {
        val event = StepFunRealtimeProtocol.parse(
            """{"type":"conversation.item.created","item":{"id":"item_text","role":"user","content":[{"type":"input_text","transcript":"你好","text":"你好"}]}}""",
        )

        assertTrue(event is StepFunRealtimeProtocol.Event.Unknown)
    }
}
