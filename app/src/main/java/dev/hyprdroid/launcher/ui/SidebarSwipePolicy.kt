package dev.hyprdroid.launcher.ui

import kotlin.math.abs

internal data class SidebarActivationZone(val left: Float, val right: Float, val top: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = left < right && top < bottom && x in left..right && y in top..bottom
}

/** One pointer stream, one axis decision. Cancelled/short/reversed pulls never complete. */
internal class SidebarSwipePolicy(private val sign: Float, private val lockSlop: Float, private val distance: Float) {
    private enum class Phase { PENDING, OWNED, REJECTED }
    private var phase = Phase.PENDING
    private var x = 0f
    private var y = 0f
    val owned get() = phase == Phase.OWNED
    val rejected get() = phase == Phase.REJECTED
    fun move(dx: Float, dy: Float) {
        x = dx; y = dy
        if (phase != Phase.PENDING) return
        if (dx * sign > lockSlop && abs(dx) > abs(dy) * 1.3f) phase = Phase.OWNED
        else if (dx * sign < -lockSlop || (abs(dy) > lockSlop && abs(dy) > abs(dx) * 1.3f) ||
            maxOf(abs(dx), abs(dy)) > lockSlop * 4) phase = Phase.REJECTED
    }
    fun finish(released: Boolean): Boolean {
        val complete = released && owned && x * sign >= distance && abs(x) > abs(y) * 1.3f
        cancel()
        return complete
    }
    fun cancel() { phase = Phase.REJECTED; x = 0f; y = 0f }
}
