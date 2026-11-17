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
import dev.hyprdroid.launcher.model.PanelSettings
import dev.hyprdroid.launcher.model.PanelSide
import dev.hyprdroid.launcher.model.PanelActivation

/** A bounded, visible touch target. Never installs a full-screen transparent input catcher. */
internal class OverlayEdgeView(context: Context, private val side: PanelSide, private val settings: PanelSettings, private val open: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(203, 166, 247); alpha = settings.visibility.alpha }
    private var x = 0f; private var y = 0f
    private var rejected = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    init {
        contentDescription = if (side == PanelSide.RIGHT) "Открыть глобальную Sidebar" else "Открыть глобальную левую панель"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        setOnClickListener { open() }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val marker = settings.markerWidthDp * d
        val left = if (side == PanelSide.LEFT) 0f else width - marker
        canvas.drawRoundRect(left, 0f, left + marker, height.toFloat(), marker / 2, marker / 2, paint)
    }
    override fun performClick(): Boolean = super.performClick()
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; rejected = false; return true }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { rejected = true; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - x) * if (side == PanelSide.RIGHT) 1 else -1; val dy = event.rawY - y
                if (abs(dy) > slop && abs(dy) > abs(dx) || dx > slop) rejected = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = (event.rawX - x) * if (side == PanelSide.RIGHT) 1 else -1; val dy = event.rawY - y
                val swipe = dx < -settings.swipeThresholdDp * resources.displayMetrics.density && -dx > abs(dy) * 1.3f
                val tap = settings.activation == PanelActivation.TAP_SWIPE && abs(dx) < slop && abs(dy) < slop
                if (!rejected && (swipe || tap)) performClick()
                return true
            }
        }
        return true
    }
}
