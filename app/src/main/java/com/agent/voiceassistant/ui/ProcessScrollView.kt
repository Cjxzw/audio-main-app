package com.agent.voiceassistant.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/** Keeps process-panel gestures inside the panel instead of letting the chat list steal them. */
class ProcessScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    /** Maximum viewport height; the child remains full-height so scrolling keeps its range. */
    var maxViewportHeight: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val maxHeight = maxViewportHeight
        if (maxHeight > 0 && measuredHeight > maxHeight) {
            setMeasuredDimension(measuredWidth, maxHeight)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            -> parent?.requestDisallowInterceptTouchEvent(true)

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            -> parent?.requestDisallowInterceptTouchEvent(true)

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }
}
