package dev.hyprdroid.launcher.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides when the catalogue must be re-read. Events that arrive while a load is running stay
 * recorded, so the next pass picks them up instead of losing them.
 */
internal class CatalogRefreshGate(private val safetyIntervalMillis: Long = SAFETY_INTERVAL_MILLIS) {
    companion object { const val SAFETY_INTERVAL_MILLIS = 15 * 60 * 1000L }

    private val lock = Any()
    private var dirty = false
    private val invalidated = mutableSetOf<String>()
    private var lastSuccess: Long? = null

    val pending: Boolean get() = synchronized(lock) { dirty }

    fun changed(packages: Set<String> = emptySet()) = synchronized(lock) {
        dirty = true
        invalidated += packages
    }

    /**
     * Returns the packages to invalidate for the next pass, or `null` when nothing is due. The cold
     * start, an explicit retry, a package event or the safety interval each force a pass; a plain
     * resume inside the interval does not.
     */
    fun take(hasApps: Boolean, force: Boolean, now: Long): Set<String>? = synchronized(lock) {
        val last = lastSuccess
        val due = force || !hasApps || dirty || last == null || now - last >= safetyIntervalMillis
        if (!due) return@synchronized null
        dirty = false
        val result = invalidated.toSet()
        invalidated.clear()
        result
    }

    fun completed(now: Long) = synchronized(lock) { lastSuccess = now }

    /** A failed pass keeps its events for the next trigger instead of retrying in a tight loop. */
    fun failed(packages: Set<String>) = synchronized(lock) {
        dirty = true
        invalidated += packages
    }
}

/**
 * Serializes catalogue loads. A request that arrives during a load waits for the mutex and then
 * joins the result instead of starting a parallel copy of the same work. The second argument of
 * [load] tells the catalogue to re-decode icons even when their timestamps did not change.
 */
internal class CatalogRefresher(
    private val gate: CatalogRefreshGate,
    private val now: () -> Long,
    private val load: suspend (Set<String>, Boolean) -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun refresh(hasApps: () -> Boolean, force: Boolean) {
        mutex.withLock {
            var forced = force
            while (true) {
                val invalidated = gate.take(hasApps(), forced, now()) ?: return
                val refreshIcons = forced
                forced = false
                if (!load(invalidated, refreshIcons)) {
                    gate.failed(invalidated)
                    return
                }
                gate.completed(now())
                if (!gate.pending) return
            }
        }
    }
}
