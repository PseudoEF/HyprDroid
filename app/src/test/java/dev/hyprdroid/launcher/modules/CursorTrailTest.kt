package dev.hyprdroid.launcher.modules

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorTrailTest {
    private val eps = 0.001f

    @Test
    fun horizontalMoveKeepsRowHeightAndSpansBothX() {
        val trail = cursorTrail(
            CursorTarget(10f, 0f, 12f),
            CursorTarget(40f, 0f, 12f),
        )

        assertEquals(10f, trail.left, eps)
        assertEquals(0f, trail.top, eps)
        assertEquals(30f, trail.width, eps)
        assertEquals(12f, trail.height, eps)
    }

    @Test
    fun horizontalMoveWidensByMinWidth() {
        val trail = cursorTrail(
            CursorTarget(10f, 0f, 12f),
            CursorTarget(40f, 0f, 12f),
            6f,
        )

        assertEquals(10f, trail.left, eps)
        assertEquals(36f, trail.width, eps)
        assertEquals(12f, trail.height, eps)
    }

    @Test
    fun verticalMoveStretchesAcrossBothRows() {
        val trail = cursorTrail(
            CursorTarget(10f, 0f, 12f),
            CursorTarget(10f, 24f, 36f),
        )

        assertEquals(10f, trail.left, eps)
        assertEquals(0f, trail.top, eps)
        assertEquals(0f, trail.width, eps)
        assertEquals(36f, trail.height, eps)
    }

    @Test
    fun diagonalMoveProducesBoundingBox() {
        val trail = cursorTrail(
            CursorTarget(10f, 0f, 12f),
            CursorTarget(40f, 24f, 36f),
        )

        assertEquals(10f, trail.left, eps)
        assertEquals(0f, trail.top, eps)
        assertEquals(30f, trail.width, eps)
        assertEquals(36f, trail.height, eps)
    }

    @Test
    fun identicalTargetsUseMinWidthAndZeroHeight() {
        val trail = cursorTrail(
            CursorTarget(10f, 5f, 17f),
            CursorTarget(10f, 5f, 17f),
            4f,
        )

        assertEquals(10f, trail.left, eps)
        assertEquals(5f, trail.top, eps)
        assertEquals(4f, trail.width, eps)
        assertEquals(0f, trail.height, eps)
    }

    @Test
    fun negativeMinWidthIsClampedToZero() {
        val identical = cursorTrail(
            CursorTarget(10f, 5f, 17f),
            CursorTarget(10f, 5f, 17f),
            -8f,
        )
        assertEquals(0f, identical.width, eps)
        assertEquals(0f, identical.height, eps)

        val horizontal = cursorTrail(
            CursorTarget(10f, 0f, 12f),
            CursorTarget(40f, 0f, 12f),
            -8f,
        )
        assertEquals(30f, horizontal.width, eps)
    }

    @Test
    fun reverseDirectionStartsAtTargetX() {
        val trail = cursorTrail(
            CursorTarget(40f, 0f, 12f),
            CursorTarget(10f, 0f, 12f),
        )

        assertEquals(10f, trail.left, eps)
        assertEquals(30f, trail.width, eps)
        assertEquals(12f, trail.height, eps)
    }
}
