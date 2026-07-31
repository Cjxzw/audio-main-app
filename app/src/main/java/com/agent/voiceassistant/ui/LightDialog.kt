package com.agent.voiceassistant.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun MaterialAlertDialogBuilder.showLightDialog(): AlertDialog {
    val dialog = show()
    dialog.window?.apply {
        val radius = 8f * context.resources.displayMetrics.density
        setBackgroundDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(Color.WHITE)
            },
        )
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes.apply { dimAmount = 0.55f }
    }
    return dialog
}
