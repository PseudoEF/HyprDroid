package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.data.CatalogRefreshGate
import dev.hyprdroid.launcher.data.CatalogRefresher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRefreshTest {
    private val safety = CatalogRefreshGate.SAFETY_INTERVAL_MILLIS

    @Test fun coldStartLoadsOnceAndPlainResumeInsideTheIntervalIsSkipped() = runBlocking {
        var now = 1_000L
        val loads = mutableListOf<Set<String>>()
        val refresher = CatalogRefresher(CatalogRefreshGate(), { now }) { invalidated, _ ->
            loads += invalidated
            true
        }
        refresher.refresh(hasApps = { false }, force = false)
        refresher.refresh(hasApps = { true }, force = false)
        now += safety - 1
        refresher.refresh(hasApps = { true }, force = false)
        assertEquals("cold start and a plain resume load once", 1, loads.size)
        now += 1
        refresher.refresh(hasApps = { true }, force = false)
        assertEquals("the safety interval re-reads the catalogue", 2, loads.size)
        assertEquals(emptySet<String>(), loads.last())
    }

    @Test fun packageEventsAreAppliedEvenWhenTheyArriveDuringALoad() = runBlocking {
        val gate = CatalogRefreshGate()
        val loads = mutableListOf<Set<String>>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val refresher = CatalogRefresher(gate, { 0L }) { invalidated, _ ->
            loads += invalidated
            if (loads.size == 1) { started.complete(Unit); release.await() }
            true
        }
        val job = launch(Dispatchers.Default) { refresher.refresh(hasApps = { true }, force = false) }
        started.await()
        gate.changed(setOf("example.app"))
        release.complete(Unit)
        job.join()
        assertEquals(listOf(emptySet<String>(), setOf("example.app")), loads)
    }

    @Test fun concurrentRequestsShareOneLoad() = runBlocking {
        var loads = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val refresher = CatalogRefresher(CatalogRefreshGate(), { 0L }) { _, _ ->
            loads++
            if (loads == 1) { started.complete(Unit); release.await() }
            true
        }
        val first = launch(Dispatchers.Default) { refresher.refresh(hasApps = { false }, force = false) }
        started.await()
        val second = launch(Dispatchers.Default) { refresher.refresh(hasApps = { true }, force = false) }
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals("the waiting request joins the running load", 1, loads)
    }

    @Test fun forcedRefreshBypassesTheSafetyInterval() = runBlocking {
        val loads = mutableListOf<Set<String>>()
        val refresher = CatalogRefresher(CatalogRefreshGate(), { 0L }) { invalidated, _ ->
            loads += invalidated
            true
        }
        refresher.refresh(hasApps = { false }, force = false)
        refresher.refresh(hasApps = { true }, force = false)
        refresher.refresh(hasApps = { true }, force = true)
        assertEquals(2, loads.size)
    }

    @Test fun failedPassKeepsItsInvalidationAndDoesNotRetryInALoop() = runBlocking {
        val gate = CatalogRefreshGate()
        var attempts = 0
        val refresher = CatalogRefresher(gate, { 0L }) { _, _ -> attempts++; false }
        gate.changed(setOf("example.app"))
        refresher.refresh(hasApps = { true }, force = false)
        assertEquals("a failure is not retried immediately", 1, attempts)
        assertTrue("events stay queued for the next trigger", gate.pending)
        assertEquals(setOf("example.app"), gate.take(hasApps = true, force = false, now = safety))
    }

    @Test fun packageEventAfterIdleTriggersExactlyOneInvalidatedPass() = runBlocking {
        val gate = CatalogRefreshGate()
        val loads = mutableListOf<Set<String>>()
        val refresher = CatalogRefresher(gate, { 0L }) { invalidated, _ ->
            loads += invalidated
            true
        }
        refresher.refresh(hasApps = { false }, force = false)
        gate.changed(setOf("first.app"))
        refresher.refresh(hasApps = { true }, force = false)
        refresher.refresh(hasApps = { true }, force = false)
        assertEquals(listOf(emptySet<String>(), setOf("first.app")), loads)
    }

    @Test fun explicitRefreshReDecodesIconsAndItsFollowUpPassDoesNot() = runBlocking {
        val gate = CatalogRefreshGate()
        val passes = mutableListOf<Pair<Set<String>, Boolean>>()
        val refresher = CatalogRefresher(gate, { 0L }) { invalidated, refreshIcons ->
            passes += invalidated to refreshIcons
            if (passes.size == 1) gate.changed(setOf("late.app"))
            true
        }
        refresher.refresh(hasApps = { true }, force = true)
        assertEquals(listOf(emptySet<String>() to true, setOf("late.app") to false), passes)
    }
}
