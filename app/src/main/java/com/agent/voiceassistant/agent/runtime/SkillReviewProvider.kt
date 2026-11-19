package com.agent.voiceassistant.agent.runtime

import kotlinx.serialization.Serializable

@Serializable
data class SkillReviewRequest(
    val requestId: String,
    val operation: String,
    val skillId: String,
    val name: String,
    val runtime: String,
    val entry: String? = null,
    val permissions: Set<String> = emptySet(),
    val manifestVersion: Int = SkillReviewPolicy.MANIFEST_VERSION,
    val reviewPolicyVersion: Int = SkillReviewPolicy.VERSION,
    val candidateDigest: String,
)

@Serializable
data class SkillReviewDecision(
    val approved: Boolean,
    val reason: String,
)

interface SkillReviewProvider {
    suspend fun review(request: SkillReviewRequest): SkillReviewDecision?
}

object ManualSkillReviewProvider : SkillReviewProvider {
    override suspend fun review(request: SkillReviewRequest): SkillReviewDecision? = null
}

object SkillReviewPolicy {
    const val MANIFEST_VERSION = 1
    const val VERSION = 1
}
