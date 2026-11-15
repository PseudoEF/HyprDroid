package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.ui.PanelPull
import org.junit.Assert.*
import org.junit.Test

class PanelPullTest {
    @Test fun reversePullClosesOnlyAfterThreshold() {
        val pull = PanelPull(1f, 64f)
        pull.scroll(40f, true)
        assertFalse(pull.finish(true))
        pull.scroll(40f, true)
        pull.scroll(30f, true)
        assertTrue(pull.finish(true))
        assertFalse(pull.finish(true))
    }
    @Test fun scrollingToBoundaryDuringSameGestureDoesNotClosePanel() {
        val pull = PanelPull(1f, 64f)
        assertEquals(0f, pull.scroll(80f, false), 0f)
        assertEquals(0f, pull.scroll(80f, true), 0f)
        assertFalse(pull.finish(true))
        pull.scroll(80f, true)
        assertTrue(pull.finish(true))
    }
    @Test fun wrongDirectionAndReversingPullDoNotDismiss() {
        val pull = PanelPull(-1f, 64f)
        pull.scroll(100f, true)
        pull.scroll(-100f, true)
        assertFalse(pull.finish(true))
        pull.scroll(-100f, true)
        pull.scroll(60f, true)
        assertFalse(pull.finish(true))
        pull.scroll(-80f, true)
        assertTrue(pull.finish(true))
    }
    @Test fun lostBoundaryAndCancelledGestureCannotLeakIntoNextGesture() {
        val pull = PanelPull(1f, 64f)
        pull.scroll(100f, true)
        assertFalse(pull.finish(false))
        pull.scroll(40f, true)
        pull.reset()
        pull.scroll(40f, true)
        assertFalse(pull.finish(true))
    }
}
