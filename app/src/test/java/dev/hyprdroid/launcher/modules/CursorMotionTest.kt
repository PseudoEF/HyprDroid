package dev.hyprdroid.launcher.modules

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorMotionTest {
    @Test
    fun textLocalCaretUsesViewportOriginAndHorizontalScrollOnce() {
        assertEquals(
            CursorTarget(22f, 41f, 53f),
            viewportCaret(Rect(35f, 3f, 36f, 15f), Offset(7f, 38f), horizontalScroll = 20),
        )
        assertNull(viewportCaret(Rect.Zero, Offset.Zero))
    }

    @Test
    fun outputAppendUsesOldRowAsScrollAnchor() {
        val previous = CursorPlacement(CursorTarget(20f, 80f, 90f), 8L, mapOf(8L to 80))
        val next = CursorPlacement(CursorTarget(20f, 80f, 90f), 9L, mapOf(8L to 63, 9L to 80))
        val movement = next.displacementFrom(previous)
        val motion = CursorMotion(previous.target, Offset(50f, 70f)).translate(movement)
        assertEquals(Offset(0f, -17f), movement)
        assertEquals(CursorTarget(20f, 63f, 73f), motion.position)
        assertEquals(Offset(50f, 70f), motion.velocity)
    }

    @Test
    fun horizontalScrollTracksTextAndMissingOutputAnchorDoesNotInventDisplacement() {
        val caret = CursorTarget(20f, 80f, 90f)
        val before = CursorPlacement(caret, inputScroll = 20)
        val after = CursorPlacement(caret, inputScroll = 31)
        assertEquals(Offset(-11f, 0f), after.displacementFrom(before))

        val oldRow = CursorPlacement(caret, 8L, mapOf(8L to 80))
        val newRow = CursorPlacement(caret, 9L, mapOf(9L to 30))
        assertEquals(Offset.Zero, newRow.displacementFrom(oldRow))
    }

    @Test
    fun springMovesOnBothAxesAndRetargetKeepsMomentum() {
        val firstGoal = CursorTarget(100f, 60f, 72f)
        val initial = CursorMotion(CursorTarget(0f, 0f, 12f))
        val moving = initial.advance(firstGoal, 0.016f)
        assertTrue(moving.position.x in 0f..100f)
        assertTrue(moving.position.top in 0f..60f)
        assertTrue(moving.velocity.x > 0f)
        assertTrue(moving.velocity.y > 0f)

        val reverse = CursorTarget(0f, 0f, 12f)
        val firstReverseFrame = moving.advance(reverse, 0.008f)
        assertTrue(firstReverseFrame.position.x > moving.position.x)
        assertTrue(firstReverseFrame.position.top > moving.position.top)

        var settled = firstReverseFrame
        repeat(120) { settled = settled.advance(reverse, 0.016f) }
        assertEquals(CursorMotion(reverse), settled)
    }

    @Test
    fun underdampedSpringOvershootsAndSettlesAtActualCaret() {
        val goal = CursorTarget(40f, 30f, 42f)
        var motion = CursorMotion(CursorTarget(0f, 0f, 12f))
        var greatestX = 0f
        repeat(120) {
            motion = motion.advance(goal, 0.008f)
            greatestX = maxOf(greatestX, motion.position.x)
        }
        assertTrue(greatestX > goal.x)
        assertEquals(CursorMotion(goal), motion)
    }
}
