package dev.hyprdroid.launcher.external

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object NativeLua {
    init { System.loadLibrary("hypr_external") }
    external fun run(source: ByteArray, state: ByteArray, event: Int): ByteArray?
}

/** One admitted operation per isolated instance. The controller owns persisted tile state. */
class ExternalLuaService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val monitor = Executors.newSingleThreadScheduledExecutor()
    private val active = AtomicBoolean(false)
    private var request: Request? = null
    private var disposed = false

    private data class Request(
        val id: Long,
        val source: ByteArray,
        val state: ByteArray,
        val reply: IExternalLuaReply,
    )

    private fun authorized() = Binder.getCallingUid() == applicationInfo.uid &&
        Binder.getCallingUid() != Process.myUid()

    private val binder = object : IExternalLua.Stub() {
        @Synchronized
        override fun createSession(id: Long, apiVersion: Int, source: SharedMemory, state: SharedMemory, reply: IExternalLuaReply) {
            if (!authorized() || disposed || request != null || active.get() || id == 0L || apiVersion != 1) {
                source.close()
                state.close()
                if (authorized()) reply.error(id, 1, 3)
                return
            }
            try {
                val script = checkedBytes(source, 16384)
                val initial = checkedBytes(state, 512)
                require(script.isNotEmpty() && script[0] != 0x1b.toByte() && 0.toByte() !in script)
                reply.asBinder().linkToDeath({ terminate() }, 0)
                request = Request(id, script, initial, reply)
            } catch (_: Exception) {
                reply.error(id, 1, 3)
            } finally {
                source.close()
                state.close()
            }
        }

        @Synchronized
        override fun dispatchEvent(id: Long, number: Int, event: Int) {
            if (!authorized() || disposed) return
            val current = request ?: return
            if (current.id != id || number != 1 || event !in 0..2 || !active.compareAndSet(false, true)) {
                current.reply.error(current.id, 1, 3)
                return
            }
            // Process-level PSS sampling complements the native arena and kernel deadline.
            monitor.scheduleAtFixedRate({
                if (active.get()) {
                    val pss = try { Debug.getPss() } catch (_: RuntimeException) { 0L }
                    if (pss <= 0 || pss > 96 * 1024L) terminate()
                }
            }, 0, 25, TimeUnit.MILLISECONDS)
            worker.execute {
                try {
                    val frame = NativeLua.run(current.source, current.state, event)
                    if (frame == null || frame.isEmpty() || frame[0] != 0.toByte()) {
                        current.reply.error(id, number, if (frame?.firstOrNull() == 1.toByte()) 1 else 2)
                    } else {
                        sealedBytes(frame).use { current.reply.render(id, number, it) }
                    }
                } catch (_: OutOfMemoryError) {
                    terminate()
                } catch (_: LinkageError) {
                    current.reply.error(id, number, 2)
                } catch (_: Exception) {
                    terminate()
                }
            }
        }

        @Synchronized
        override fun disposeSession(id: Long) {
            if (!authorized() || request?.id != id) return
            disposed = true
            request = null
            terminate()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
        terminate()
        return false
    }

    override fun onDestroy() {
        monitor.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun terminate() = Process.killProcess(Process.myPid())
}
