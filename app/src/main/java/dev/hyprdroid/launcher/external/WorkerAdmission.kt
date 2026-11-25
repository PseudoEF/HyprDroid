package dev.hyprdroid.launcher.external

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal class ExternalLuaBusy : Exception()

/** Bounds running work and pending work. Preserve FIFO within each priority. */
internal class WorkerAdmission(
    private val active: Int,
    private val pending: Int,
    private val waitMillis: Long,
) {
    enum class Priority { USER, NORMAL, TIMER }

    private class Request(val priority: Priority) {
        val ready = CompletableDeferred<Unit>()
        var granted = false
    }

    private val lock = Any()
    private val waiting = mutableListOf<Request>()
    private var running = 0
    private var userBurst = 0

    suspend fun <T> execute(
        rejected: () -> T,
        priority: Priority = Priority.NORMAL,
        operation: suspend () -> T,
    ): T {
        val request = Request(priority)
        var immediate = false
        val accepted = synchronized(lock) {
            when {
                running < active && waiting.isEmpty() -> {
                    grant(request)
                    immediate = true
                    true
                }
                waiting.size < pending && (priority != Priority.TIMER || waiting.size < pending - 1) -> {
                    waiting.add(request)
                }
                else -> false
            }
        }
        if (!accepted) return rejected()
        if (immediate) request.ready.complete(Unit)

        try {
            val deadline = if (priority == Priority.TIMER) minOf(waitMillis, 1_000L) else waitMillis
            if (withTimeoutOrNull(deadline) { request.ready.await(); true } != true) return rejected()
            return operation()
        } finally {
            val releasedTo = synchronized(lock) {
                if (request.granted) {
                    running--
                    val user = waiting.indexOfFirst { it.priority == Priority.USER }
                    val background = waiting.indexOfFirst { it.priority != Priority.USER }
                    val next = when {
                        user == -1 -> background
                        background == -1 || userBurst < 2 -> user
                        else -> background
                    }
                    if (next != -1) {
                        val selected = waiting.removeAt(next)
                        userBurst = if (selected.priority == Priority.USER && background != -1) userBurst + 1 else 0
                        grant(selected)
                        selected
                    } else {
                        userBurst = 0
                        null
                    }
                } else {
                    waiting.remove(request)
                    null
                }
            }
            releasedTo?.ready?.complete(Unit)
        }
    }

    private fun grant(request: Request) {
        running++
        request.granted = true
    }
}
