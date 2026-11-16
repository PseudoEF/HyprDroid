package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.ui.SidebarActivationZone
import dev.hyprdroid.launcher.ui.SidebarSwipePolicy
import org.junit.Assert.*
import org.junit.Test

class SidebarSwipePolicyTest {
    @Test fun zoneExcludesPhysicalEdgeAndVerticalEnds() {
        val zone = SidebarActivationZone(240f, 280f, 24f, 600f)
        assertTrue(zone.contains(260f, 300f))
        assertFalse(zone.contains(299f, 300f))
        assertFalse(zone.contains(200f, 300f))
        assertFalse(zone.contains(260f, 10f))
        assertFalse(zone.contains(260f, 610f))
        assertFalse(SidebarActivationZone(-1f, -10f, 0f, 1f).contains(-5f, .5f))
    }
    @Test fun horizontalIntentionLocksBeforePagerButRequiresFullReleaseDistance() {
        val policy = SidebarSwipePolicy(-1f, 4f, 48f)
        policy.move(-5f, 1f)
        assertTrue(policy.owned)
        policy.move(-47f, 4f)
        assertFalse(policy.finish(true))
        val complete = SidebarSwipePolicy(-1f, 4f, 48f)
        complete.move(-60f, 8f)
        assertTrue(complete.finish(true))
        assertFalse(complete.finish(true))
    }
    @Test fun verticalWrongDirectionAmbiguousAndCancelledStreamsNeverOpen() {
        for ((x, y) in listOf(1f to 12f, 12f to 1f, -30f to 30f)) {
            val policy = SidebarSwipePolicy(-1f, 4f, 48f)
            policy.move(x, y)
            assertTrue(policy.rejected)
            policy.move(-100f, 0f)
            assertFalse(policy.finish(true))
        }
        val cancelled = SidebarSwipePolicy(-1f, 4f, 48f)
        cancelled.move(-100f, 0f)
        assertFalse(cancelled.finish(false))
        val reversed = SidebarSwipePolicy(-1f, 4f, 48f)
        reversed.move(-80f, 0f); reversed.move(-20f, 0f)
        assertFalse(reversed.finish(true))
    }
}
