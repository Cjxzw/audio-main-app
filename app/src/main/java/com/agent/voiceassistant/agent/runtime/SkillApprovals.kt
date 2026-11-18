package com.agent.voiceassistant.agent.runtime

import android.content.Context
import java.io.File

object SkillApprovals {
    @Volatile private var instance: SkillApprovalStore? = null
    fun get(context: Context): SkillApprovalStore = instance ?: synchronized(this) {
        instance ?: run {
            val root = File(context.applicationContext.filesDir, "agent-runtime")
            SkillApprovalStore(root, File(root, "workspace"), SkillRegistry(
                File(root, "skills"), File(root, "skills-disabled"), File(root, "skills-deleted"),
                File(root, "skills-user-modified"), File(root, "system-skills"), File(root, "system-skills-disabled"),
            )).also { instance = it }
        }
    }
}
