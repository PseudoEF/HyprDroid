package dev.hyprdroid.launcher.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileScheduleFairnessTest {
    @Test fun aSlowTimerLeavesAnIdleIntervalAfterCompletion() {
        val schedule = TileScheduleV2()
        schedule.configure(listOf(TileTimer("clock", 1_000)), 0)
        schedule.setActive(true, 0)
        val fired = schedule.poll(1_000).single()
        schedule.completed(fired, 2_700)
        assertEquals(1_000L, schedule.delayUntilNext(2_700))
        assertTrue(schedule.poll(3_699).isEmpty())
        assertEquals(listOf(fired), schedule.poll(3_700))
    }
}
