package com.bagridmaster.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView
import androidx.core.view.isNotEmpty

/** Short information remains a panel drag surface; only overflowing text consumes scrolling. */
internal class ContentScrollView(context: Context) : ScrollView(context) {
    private fun overflows() = isNotEmpty() && getChildAt(0).height > height - paddingTop - paddingBottom
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        overflows() && super.onInterceptTouchEvent(event)

    // This is deliberately only a scrolling surface; taps never trigger recognition.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = overflows() && super.onTouchEvent(event)
}
