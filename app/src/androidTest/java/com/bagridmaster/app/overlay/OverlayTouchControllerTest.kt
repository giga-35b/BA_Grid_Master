package com.bagridmaster.app.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Real ViewGroup dispatch, including synthetic CANCEL during child removal; no overlay permission. */
@RunWith(AndroidJUnit4::class)
class OverlayTouchControllerTest {
    @Test fun draggingButtonDefersReparentingUntilAfterUpDispatch() = onMain {
        val panel = Panel()
        panel.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        panel.touch(MotionEvent.ACTION_MOVE, 450f, 50f, 50)
        panel.touch(MotionEvent.ACTION_UP, 450f, 50f, 100)
        assertEquals(0, panel.finishes)
        assertEquals(1, panel.root.posted.size)
        panel.root.drainPosted()
        assertEquals(1, panel.finishes)
        assertEquals(0, panel.clicks)
        assertEquals(0, panel.longClicks)
        assertSame(panel.root, panel.button.parent)
        assertEquals(2, panel.root.childCount)
        panel.controller.dispose()
    }

    @Test fun onlyButtonCanClickOrLongClick() = onMain {
        val panel = Panel()
        assertFalse(panel.root.isClickable)
        assertFalse(panel.root.isLongClickable)
        assertFalse(panel.root.performClick())
        // Info text and panel padding must neither analyze nor clear the existing result.
        for (point in listOf(150f to 50f, 400f to 150f)) {
            panel.touch(MotionEvent.ACTION_DOWN, point.first, point.second, 0)
            panel.touch(MotionEvent.ACTION_UP, point.first, point.second, 100)
            panel.touch(MotionEvent.ACTION_DOWN, point.first, point.second, 0)
            panel.touch(MotionEvent.ACTION_UP, point.first, point.second, 2000)
        }
        assertEquals(0, panel.clicks)
        assertEquals(0, panel.longClicks)
        panel.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        panel.touch(MotionEvent.ACTION_UP, 50f, 50f, 100)
        assertEquals(1, panel.clicks)
        panel.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        panel.touch(MotionEvent.ACTION_UP, 50f, 50f, ViewConfiguration.getLongPressTimeout().toLong())
        assertEquals(1, panel.longClicks)
        assertEquals(1, panel.clicks)
        panel.controller.dispose()
    }

    @Test fun infoAreaStillDragsWithoutAnalyzing() = onMain {
        val panel = Panel()
        panel.touch(MotionEvent.ACTION_DOWN, 150f, 50f, 0)
        panel.touch(MotionEvent.ACTION_MOVE, 450f, 50f, 50)
        panel.touch(MotionEvent.ACTION_UP, 450f, 50f, 100)
        assertEquals(300 to 0, panel.position)
        panel.root.drainPosted()
        assertEquals(1, panel.finishes)
        assertEquals(0, panel.clicks)
        panel.controller.dispose()
    }

    @Test fun removingPressedButtonOnlyCancelsWithoutRebuildingAgain() = onMain {
        val panel = Panel()
        panel.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        panel.touch(MotionEvent.ACTION_MOVE, 450f, 50f, 50)
        // Mirrors a settings rebuild while a child is an active touch target.
        panel.root.removeAllViews()
        assertEquals(0, panel.root.childCount)
        assertEquals(0, panel.root.posted.size)
        assertEquals(0, panel.finishes)
        assertEquals(0, panel.clicks)
        panel.controller.dispose()
    }

    @Test fun dragOutAndBackDoesNotClick() = onMain {
        val panel = Panel()
        panel.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        panel.touch(MotionEvent.ACTION_MOVE, 450f, 50f, 50)
        panel.touch(MotionEvent.ACTION_UP, 50f, 50f, 100)
        panel.root.drainPosted()
        assertEquals(1, panel.finishes)
        assertEquals(0, panel.clicks)
        panel.controller.dispose()
    }

    @Test fun explicitCancelNeverSnapsOrClicks() = onMain {
        val panel = Panel()
        panel.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        panel.touch(MotionEvent.ACTION_MOVE, 450f, 50f, 50)
        panel.touch(MotionEvent.ACTION_CANCEL, 450f, 50f, 100)
        panel.root.drainPosted()
        assertEquals(0, panel.finishes)
        assertEquals(0, panel.clicks)
        panel.controller.dispose()
    }

    @Test fun disposeRemovesPendingFinishAndStaleRunnableIsInert() = onMain {
        val panel = Panel()
        panel.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        panel.touch(MotionEvent.ACTION_UP, 450f, 50f, 100)
        val pending = panel.root.posted.single()
        panel.controller.dispose()
        assertEquals(0, panel.root.posted.size)
        pending.run()
        assertEquals(0, panel.finishes)
        assertEquals(0, panel.clicks)
    }

    @Test fun startingAnotherGestureInvalidatesPendingFinish() = onMain {
        val panel = Panel()
        panel.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        panel.touch(MotionEvent.ACTION_UP, 450f, 50f, 100)
        val stale = panel.root.posted.single()
        panel.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 200)
        stale.run()
        assertEquals(0, panel.finishes)
        assertEquals(0, panel.root.posted.size)
        panel.touch(MotionEvent.ACTION_UP, 50f, 50f, 250)
        assertEquals(1, panel.clicks)
        panel.controller.dispose()
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private class Panel {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = PostingLayout(context)
        val button = TextView(context)
        private val info = TextView(context)
        var clicks = 0
        var longClicks = 0
        var finishes = 0
        var position = 0 to 0

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.addView(button, LinearLayout.LayoutParams(100, 100))
            root.addView(info, LinearLayout.LayoutParams(200, 100))
            root.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 500, 200)
        }

        val controller = OverlayTouchController(
            root, button, { position }, { x, y -> position = x to y },
            onDragFinished = {
                finishes++
                root.removeAllViews()
                root.addView(info)
                root.addView(button)
            },
            onClick = { clicks++ },
            onLongClick = { longClicks++; true },
        )

        fun touch(action: Int, x: Float, y: Float, time: Long) {
            val event = MotionEvent.obtain(0, time, action, x, y, 0)
            try { root.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    /** Hold posted work explicitly so assertions can cover the in-dispatch boundary. */
    private class PostingLayout(context: Context) : LinearLayout(context) {
        val posted = mutableListOf<Runnable>()
        override fun post(action: Runnable): Boolean {
            posted += action
            return true
        }
        override fun removeCallbacks(action: Runnable): Boolean = posted.remove(action)
        fun drainPosted() {
            val tasks = posted.toList()
            posted.clear()
            tasks.forEach { it.run() }
        }
    }
}
