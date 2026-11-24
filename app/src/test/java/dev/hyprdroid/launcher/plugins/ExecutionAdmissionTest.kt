package dev.hyprdroid.launcher.plugins

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ExecutionAdmissionTest {
    @Test fun revokedGenerationCancelsWaitingAndExecutingCalls() = runBlocking {
        val gate = ExecutionAdmission()
        val prepared = CompletableDeferred<Unit>()
        val executing = CompletableDeferred<Unit>()
        val allowExecution = CompletableDeferred<Unit>()
        val allowReply = CompletableDeferred<Unit>()
        val waiting = async {
            gate.run(0) {
                prepared.complete(Unit)
                allowExecution.await()
                error("Revoked waiter executed")
            }
        }
        val running = async {
            gate.run(0) {
                executing.complete(Unit)
                allowReply.await()
                error("Revoked worker returned")
            }
        }
        prepared.await()
        executing.await()
        gate.invalidate()
        assertNull(waiting.await())
        assertNull(running.await())
        assertNull(gate.run(0) { error("Stale generation executed") })
        assertFalse(gate.dispatch(0) { error("Stale Binder call enqueued") })
        assertTrue(gate.dispatch(1) {})
        assertFalse(gate.commit(0) { error("Stale reply committed") })
    }

    @Test fun invalidationWaitsForAdmittedDiskWriteAndFrame() = runBlocking {
        val gate = ExecutionAdmission()
        val persist = CompletableDeferred<Unit>()
        val publish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val commit = async(start = CoroutineStart.UNDISPATCHED) {
            gate.commit(0) {
                events += "writing"
                persist.await()
                events += "stored"
                publish.await()
                events += "rendered"
            }
        }
        val revoke = async(start = CoroutineStart.UNDISPATCHED) {
            gate.invalidate()
            events += "revoked"
        }
        assertFalse(revoke.isCompleted)
        persist.complete(Unit)
        yield()
        assertFalse(revoke.isCompleted)
        publish.complete(Unit)
        assertTrue(commit.await())
        revoke.await()
        assertEquals(listOf("writing", "stored", "rendered", "revoked"), events)
        assertFalse(gate.commit(0) { error("Published after revocation") })
    }

    @Test fun callerCancellationDoesNotReleaseInFlightCommitFence() = runBlocking {
        val gate = ExecutionAdmission()
        val written = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            gate.commit(0) { written.await() }
        }
        operation.cancel()
        val revoke = async(start = CoroutineStart.UNDISPATCHED) { gate.invalidate() }
        assertFalse(revoke.isCompleted)
        written.complete(Unit)
        revoke.await()
        assertFalse(gate.commit(0) { error("Old commit was admitted") })
    }
}
