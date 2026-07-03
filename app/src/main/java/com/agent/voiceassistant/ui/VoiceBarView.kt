package com.agent.voiceassistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.agent.voiceassistant.R
import timber.log.Timber
import kotlin.math.roundToInt

class VoiceBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.gray_200)
    }

    private val barCount = 24
    private var level = 0.0f

    private val barWidth = resources.getDimension(R.dimen.voice_bar_bar_width)
    private val barGap = resources.getDimension(R.dimen.voice_bar_bar_gap)

    private var lastNonZeroLevel = 0L

    init {
        Timber.d("VoiceBarView init: barWidth=$barWidth, barGap=$barGap, barCount=$barCount")
    }

    fun setLevel(newLevel: Float) {
        val clamped = newLevel.coerceIn(0f, 1f)
        if (clamped != level) {
            level = clamped
            if (level > 0.01f) {
                lastNonZeroLevel = System.currentTimeMillis()
            }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activeCount = (level * barCount).roundToInt()
        for (i in 0 until barCount) {
            val left = i * (barWidth + barGap)
            val right = left + barWidth
            if (i < activeCount) {
                barPaint.color = when {
                    i < barCount * 0.5 -> 0xFF4CAF50.toInt()
                    i < barCount * 0.8 -> 0xFFFFC107.toInt()
                    else -> 0xFFF44336.toInt()
                }
                canvas.drawRoundRect(left, 0f, right, height.toFloat(), 2f, 2f, barPaint)
            } else {
                canvas.drawRoundRect(left, 0f, right, height.toFloat(), 2f, 2f, bgPaint)
            }
        }
    }
}
