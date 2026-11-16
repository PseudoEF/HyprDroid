package dev.hyprdroid.launcher.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** A bounded, visible touch target. Never installs a full-screen transparent input catcher. */
internal class OverlayEdgeView(context: Context, private val open: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(203, 166, 247); alpha = 160 }
    private var x = 0f; private var y = 0f
    private var rejected = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    init {
        contentDescription = "Открыть глобальную Sidebar"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        setOnClickListener { open() }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        canvas.drawRoundRect(width - 5 * d, height * .28f, width - 2 * d, height * .72f, 2 * d, 2 * d, paint)
    }
    override fun performClick(): Boolean = super.performClick()
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; rejected = false; return true }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { rejected = true; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - x; val dy = event.rawY - y
                if (abs(dy) > slop && abs(dy) > abs(dx) || dx > slop) rejected = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - x; val dy = event.rawY - y
                if (!rejected && (dx < -48 * resources.displayMetrics.density && -dx > abs(dy) * 1.3f || abs(dx) < slop && abs(dy) < slop)) performClick()
                return true
            }
        }
        return true
    }
}
