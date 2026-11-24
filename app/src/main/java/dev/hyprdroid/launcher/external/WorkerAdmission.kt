package dev.hyprdroid.launcher.external

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

internal class ExternalLuaBusy : Exception()

/** Bounds both in-flight calls and the number of callers allowed to suspend. */
internal class WorkerAdmission(active: Int, pending: Int, private val waitMillis: Long) {
    private val slots = Semaphore(active)
    private val outstanding = AtomicInteger()
    private val limit = active + pending

    fun hasDemand(): Boolean = outstanding.get() != 0

    suspend fun <T> execute(rejected: () -> T, operation: suspend () -> T): T {
        while (true) {
            val count = outstanding.get()
            if (count >= limit) return rejected()
            if (outstanding.compareAndSet(count, count + 1)) break
        }
        var acquired = false
        try {
            if (withTimeoutOrNull(waitMillis) {
                    slots.acquire()
                    acquired = true
                    true
                } != true
            ) return rejected()
            return operation()
        } finally {
            if (acquired) slots.release()
            outstanding.decrementAndGet()
        }
    }
}
