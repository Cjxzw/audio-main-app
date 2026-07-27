package com.agent.voiceassistant.data

import org.junit.Assert.assertTrue
import org.junit.Test

class RuleLedgerTest {

    @Test
    fun `patches preserve the baseline prefix and apply in revision order`() {
        val original = rule(version = 1, body = "回答保持简洁。")
        val baseline = ConversationRuleLedger(
            baselineRevision = 1,
            baselineRules = listOf(original),
            appliedRevision = 1,
        )
        val baselineText = renderRuleLedger(baseline)

        val updated = original.copy(body = "先说结论，再补充细节。", version = 2)
        val patched = baseline.copy(
            appliedRevision = 2,
            patches = mutableListOf(RuleChange(2, RuleOperation.UPDATE, updated)),
        )
        val patchedText = renderRuleLedger(patched)

        assertTrue(patchedText.startsWith(baselineText.substringBefore("规则解释：")))
        assertTrue(patchedText.indexOf("回答保持简洁。") < patchedText.indexOf("先说结论，再补充细节。"))
        assertTrue(patchedText.contains("规则增量更新（版本 2）"))
    }

    @Test
    fun `delete patch explicitly revokes the baseline rule`() {
        val original = rule(version = 1, body = "使用正式语气。")
        val ledger = ConversationRuleLedger(
            baselineRevision = 1,
            baselineRules = listOf(original),
            appliedRevision = 2,
            patches = mutableListOf(RuleChange(2, RuleOperation.DELETE, original)),
        )

        val rendered = renderRuleLedger(ledger)

        assertTrue(rendered.contains("操作：DELETE"))
        assertTrue(rendered.contains("该规则已删除，后续不得再遵循其正文。"))
    }

    @Test
    fun `disable and enable patches preserve the rule body for reactivation`() {
        val active = rule(version = 1, body = "使用正式语气。")
        val disabled = active.copy(version = 2, enabled = false)
        val enabled = active.copy(version = 3, enabled = true)
        val ledger = ConversationRuleLedger(
            baselineRevision = 1,
            baselineRules = listOf(active),
            appliedRevision = 3,
            patches = mutableListOf(
                RuleChange(2, RuleOperation.DISABLE, disabled),
                RuleChange(3, RuleOperation.ENABLE, enabled),
            ),
        )

        val rendered = renderRuleLedger(ledger)

        assertTrue(rendered.contains("该规则已停用"))
        assertTrue(rendered.lastIndexOf("使用正式语气。") > rendered.indexOf("操作：ENABLE"))
    }

    private fun rule(version: Int, body: String) = UserRule(
        id = "rule-1",
        title = "表达方式",
        body = body,
        version = version,
        createdAt = 1,
        updatedAt = version.toLong(),
    )
}
