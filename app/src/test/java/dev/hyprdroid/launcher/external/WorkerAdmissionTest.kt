package dev.hyprdroid.launcher.external

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerAdmissionTest {
    @Test fun boundsBurstAndRestoresCancelledSlots() = runBlocking {
        val admission = WorkerAdmission(active = 2, pending = 4, waitMillis = 5000)
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val jobs = (0..5).map { id ->
            async(start = CoroutineStart.UNDISPATCHED) {
                admission.execute({ -1 }) {
                    entered += id
                    release.await()
                    id
                }
            }
        }
        repeat(1000) { assertEquals(-1, admission.execute({ -1 }) { error("overflow executed") }) }
        assertEquals(listOf(0, 1), entered)
        jobs[2].cancelAndJoin()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            admission.execute({ -1 }) { entered += 6; 6 }
        }
        release.complete(Unit)
        assertEquals(listOf(0, 1, 3, 4, 5), jobs.filterIndexed { i, _ -> i != 2 }.map { it.await() })
        assertEquals(6, replacement.await())
        assertEquals(listOf(0, 1, 3, 4, 5, 6), entered)
    }

    @Test fun timeoutDoesNotLeakCapacity() = runBlocking {
        val admission = WorkerAdmission(active = 1, pending = 1, waitMillis = 25)
        val release = CompletableDeferred<Unit>()
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            admission.execute({ -1 }) { release.await(); 1 }
        }
        assertEquals(-1, admission.execute({ -1 }) { error("timed-out waiter executed") })
        release.complete(Unit)
        assertEquals(1, active.await())
        assertEquals(2, admission.execute({ -1 }) { 2 })
    }
}
