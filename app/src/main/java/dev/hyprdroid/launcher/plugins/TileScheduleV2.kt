package dev.hyprdroid.launcher.plugins

internal data class TileTimer(val id: String, val intervalMs: Long)

internal sealed interface TileSignal {
    data object Resume : TileSignal
    data class Timer(val id: String) : TileSignal
}

/** Core owns the clock and supplies the monotonic time. Polling never replays missed intervals. */
internal class TileScheduleV2 {
    private var active = false
    private var deadlines = emptyMap<String, Long>()
    private var timers = emptyList<TileTimer>()

    fun configure(requested: List<TileTimer>, nowMs: Long) {
        require(requested.size <= 2 && requested.map { it.id }.toSet().size == requested.size)
        require(requested.all { tileEventId.matches(it.id) && it.intervalMs in 1_000..86_400_000 })
        if (requested == timers) return
        timers = requested.toList()
        if (active) deadlines = timers.associate { it.id to nowMs + it.intervalMs }
    }

    fun setActive(visibleAndStarted: Boolean, nowMs: Long): TileSignal.Resume? {
        if (active == visibleAndStarted) return null
        active = visibleAndStarted
        deadlines = if (active) timers.associate { it.id to nowMs + it.intervalMs } else emptyMap()
        return if (active) TileSignal.Resume else null
    }

    /** A slow tick must leave an idle interval instead of immediately starting another worker. */
    fun completed(timer: TileSignal.Timer, nowMs: Long) {
        if (!active) return
        val interval = timers.firstOrNull { it.id == timer.id }?.intervalMs ?: return
        deadlines = deadlines + (timer.id to maxOf(deadlines.getValue(timer.id), nowMs + interval))
    }

    fun delayUntilNext(nowMs: Long): Long? = deadlines.values.minOrNull()?.let { (it - nowMs).coerceAtLeast(1) }

    fun poll(nowMs: Long): List<TileSignal.Timer> {
        if (!active) return emptyList()
        val due = timers.filter { nowMs >= deadlines.getValue(it.id) }
        deadlines = deadlines + due.associate { it.id to nowMs + it.intervalMs }
        return due.map { TileSignal.Timer(it.id) }
    }
}
