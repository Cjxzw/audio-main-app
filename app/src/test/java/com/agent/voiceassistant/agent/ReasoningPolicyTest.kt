package com.agent.voiceassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningPolicyTest {
    @Test
    fun `explicit brainstorming starts deep reasoning immediately`() {
        assertTrue(ReasoningPolicy.requestsDeepReasoning("我们来头脑风暴一下这个产品"))
        assertTrue(ReasoningPolicy.requestsDeepReasoning("你认真想一想再回答"))
    }

    @Test
    fun `ordinary conversation stays on fast path`() {
        assertFalse(ReasoningPolicy.requestsDeepReasoning("今天天气怎么样"))
        assertFalse(ReasoningPolicy.requestsDeepReasoning("你会控制米家设备吗"))
    }
}
