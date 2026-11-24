package dev.hyprdroid.launcher.external

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** An unused isolated binding. The same permits account for idle and assigned workers. */
internal class PrewarmedWorker(
    private val processes: Semaphore,
    private val demandPending: () -> Boolean,
    private val healthy: () -> Boolean,
    private val unconfirmedExit: () -> Unit,
    private val unexpectedExit: () -> Unit,
) : ComponentCallbacks2 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var visible = 0
    private var registered = false
    private var reserve: Reserve? = null

    private class Reserve(val binding: IsolatedWorkerBinding) {
        lateinit var job: Job
        val taken = CompletableDeferred<Unit>()
        var ready = false
        var retiring = false
    }

    @Synchronized
    fun visibleTile(app: Context): () -> Unit {
        if (!registered) {
            app.registerComponentCallbacks(this)
            registered = true
        }
        visible++
        var released = false
        return {
            synchronized(this) {
                if (!released) {
                    released = true
                    visible--
                    if (visible == 0) discard()
                }
            }
        }
    }

    /** Only initial mount and a real user action may request preparation. No periodic refill. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun prepare(app: Context) {
        if (visible == 0 || reserve != null || demandPending() || !healthy() || !processes.tryAcquire()) return
        val candidate = Reserve(IsolatedWorkerBinding(app))
        reserve = candidate
        candidate.job = scope.launch(start = CoroutineStart.ATOMIC) {
            var launched = false
            try {
                if (synchronized(this@PrewarmedWorker) { candidate.retiring || demandPending() || !healthy() }) return@launch
                launched = candidate.binding.start()
                if (launched && withTimeoutOrNull(START_MS) { candidate.binding.connected.await() } != null) {
                    synchronized(this@PrewarmedWorker) {
                        if (!candidate.retiring) candidate.ready = true
                    }
                    Log.i(TAG, "reserve=ready")
                    withTimeoutOrNull(IDLE_MS) {
                        select<Unit> {
                            candidate.taken.onAwait { }
                            candidate.binding.disconnected.onAwait { }
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Demand still has the normal cold path.
            } finally {
                val owned = synchronized(this@PrewarmedWorker) {
                    if (reserve !== candidate) false
                    else {
                        candidate.retiring = true
                        true
                    }
                }
                if (owned) withContext(NonCancellable) {
                    if (!candidate.binding.close(assigned = false)) unconfirmedExit()
                    if (candidate.binding.failedUnexpectedly()) unexpectedExit()
                    synchronized(this@PrewarmedWorker) {
                        reserve = null
                        processes.release()
                    }
                    if (launched) Log.i(TAG, "reserve=unused")
                }
            }
        }
    }

    @Synchronized
    fun readyId(): Long? = reserve?.takeIf { it.ready && !it.retiring }?.binding?.id

    @Synchronized
    fun take(): IsolatedWorkerBinding? {
        val candidate = reserve ?: return null
        if (!candidate.ready || candidate.retiring || candidate.binding.disconnected.isCompleted) {
            discard()
            return null
        }
        reserve = null
        candidate.taken.complete(Unit)
        Log.i(TAG, "reserve=hit")
        return candidate.binding
    }

    @Synchronized
    fun discard() {
        val candidate = reserve ?: return
        candidate.retiring = true
        candidate.job.cancel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onLowMemory() = discard()
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) discard()
    }

    private companion object {
        const val TAG = "HyprLuaReserve"
        const val START_MS = 5_000L
        const val IDLE_MS = 3_000L
    }
}
