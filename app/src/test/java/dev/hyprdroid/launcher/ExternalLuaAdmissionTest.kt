package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.plugins.ExternalLuaSessionGate
import dev.hyprdroid.launcher.plugins.ExecutionAdmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLuaAdmissionTest {
    @Test
    fun rejectsInvalidSessionLifecycleAndEvents() {
        val gate = ExternalLuaSessionGate()
        assertFalse(gate.dispatch(1, 1, 0))
        assertFalse(gate.create(0, 1))
        assertFalse(gate.create(1, 2))
        assertTrue(gate.create(1, 1))
        assertFalse(gate.create(2, 1))
        assertFalse(gate.dispose(2))
        assertFalse(gate.dispatch(2, 1, 1))
        assertFalse(gate.dispatch(1, 0, 1))
        assertFalse(gate.dispatch(1, 1, 9))
        assertTrue(gate.dispatch(1, 1, 1))
        assertFalse(gate.dispatch(1, 1, 1))
        assertTrue(gate.dispose(1))
        assertFalse(gate.create(1, 1))
        assertFalse(gate.dispatch(1, 1, 1))
    }

    @Test
    fun generationChangeCancelsOperationAndRejectsAlreadyStaleStarts() = runBlocking {
        val admission = ExecutionAdmission()
        val started = CompletableDeferred<Unit>()
        val disposed = CompletableDeferred<Unit>()
        val pending = async {
            admission.run<String>(0) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    disposed.complete(Unit)
                }
            }
        }
        started.await()
        admission.invalidate()
        assertNull(pending.await())
        assertTrue(disposed.isCompleted)
        assertNull(admission.run(0) { error("stale operation started") })
    }
}
