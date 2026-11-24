package dev.hyprdroid.launcher.external

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.plugins.ExternalLuaSessionGate
import dev.hyprdroid.launcher.plugins.isPluginText
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object NativeLua {
    init { System.loadLibrary("hypr_external") }
    external fun runV2(source: ByteArray, context: ByteArray, event: Int, capabilities: Int): ByteArray?
    external fun run(source: ByteArray, state: ByteArray, event: Int): ByteArray?
}

/** One admitted operation per isolated instance. The controller owns persisted tile state. */
class ExternalLuaService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val monitor = Executors.newSingleThreadScheduledExecutor()
    private val active = AtomicBoolean(false)
    private var request: Request? = null
    private val gate = ExternalLuaSessionGate()

    private data class Request(
        val source: ByteArray,
        val state: ByteArray,
        val reply: IExternalLuaReply,
        val capabilities: Int = 0,
        val eventV2: Int? = null,
    )

    private fun authorized() = Binder.getCallingUid() == applicationInfo.uid &&
        Binder.getCallingUid() != Process.myUid()

    private val binder = object : IExternalLua.Stub() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // The generated stub for the app's minimum SDK accepts trailing data.
            // These primitive-only messages have an exact wire size.
            when (code) {
                IExternalLua.Stub.TRANSACTION_dispatchEvent -> {
                    data.enforceInterface(requireNotNull(interfaceDescriptor))
                    if (data.dataAvail() != Long.SIZE_BYTES + 2 * Int.SIZE_BYTES) return false
                    dispatchEvent(data.readLong(), data.readInt(), data.readInt())
                    return true
                }
                IExternalLua.Stub.TRANSACTION_disposeSession -> {
                    data.enforceInterface(requireNotNull(interfaceDescriptor))
                    if (data.dataAvail() != Long.SIZE_BYTES) return false
                    disposeSession(data.readLong())
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }

        @Synchronized
        override fun createSession(id: Long, apiVersion: Int, source: SharedMemory?, state: SharedMemory?, reply: IExternalLuaReply?) {
            try {
                if (!authorized() || source == null || state == null || reply == null ||
                    !gate.create(id, apiVersion)
                ) return
                val script = checkedBytes(source, 16384)
                val initial = checkedBytes(state, 512)
                require(script.isNotEmpty() && script[0] != 0x1b.toByte() && 0.toByte() !in script)
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(initial)).toString()
                require(text.isPluginText(512))
                reply.asBinder().linkToDeath({ terminate() }, 0)
                request = Request(script, initial, reply)
            } catch (_: OutOfMemoryError) {
                terminate()
            } catch (_: Exception) {
                // Malformed admission leaves no live session or replayable callback.
                terminate()
            } finally {
                source?.close()
                state?.close()
            }
        }

        @Synchronized
        override fun createSessionV2(
            id: Long, wireVersion: Int, capabilities: Int, eventKind: Int,
            source: SharedMemory?, context: SharedMemory?, reply: IExternalLuaReply?,
        ) {
            try {
                if (!authorized() || source == null || context == null || reply == null ||
                    wireVersion != TILE_WIRE_V2 || capabilities !in 0..7 || eventKind !in 0..5 ||
                    !gate.create(id, 2, expectedApi = 2)
                ) return
                val script = checkedBytes(source, TILE_SOURCE_LIMIT)
                val payload = checkedBytes(context, TILE_CONTEXT_V2_LIMIT)
                require(script.isNotEmpty() && script[0] != 0x1b.toByte() && 0.toByte() !in script)
                reply.asBinder().linkToDeath({ terminate() }, 0)
                request = Request(script, payload, reply, capabilities, eventKind)
            } catch (_: OutOfMemoryError) {
                terminate()
            } catch (_: Exception) {
                terminate()
            } finally {
                source?.close()
                context?.close()
            }
        }

        @Synchronized
        override fun dispatchEvent(id: Long, number: Int, event: Int) {
            if (!authorized()) return
            val current = request ?: return
            if (!gate.dispatch(id, number, event)) return
            active.set(true)
            // Process-level PSS sampling complements the native arena and kernel deadline.
            monitor.scheduleAtFixedRate({
                if (active.get()) {
                    val pss = try { Debug.getPss() } catch (_: RuntimeException) { 0L }
                    if (pss <= 0 || pss > 96 * 1024L) {
                        if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaPressure", "worker PSS guard pssKiB=$pss")
                        terminate()
                    }
                }
            }, 0, 25, TimeUnit.MILLISECONDS)
            worker.execute {
                try {
                    val startedAt = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
                    val frame = if (current.eventV2 != null) {
                        NativeLua.runV2(current.source, current.state, current.eventV2, current.capabilities)
                    } else NativeLua.run(current.source, current.state, event)
                    if (BuildConfig.V2_FIXTURE_LUA) {
                        Log.i("HyprLuaGate", "operation=$id phase=lua luaMs=${SystemClock.elapsedRealtime() - startedAt}")
                    }
                    if (frame == null || frame.isEmpty() || frame[0] != 0.toByte()) {
                        current.reply.error(id, number, if (frame?.firstOrNull() == 1.toByte()) 1 else 2)
                    } else {
                        sealedBytes(frame).use { current.reply.render(id, number, it) }
                    }
                } catch (_: OutOfMemoryError) {
                    if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaPressure", "worker caught managed OutOfMemoryError")
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
            if (!authorized() || !gate.dispose(id)) return
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

    private fun terminate() {
        if (BuildConfig.V2_FIXTURE_LUA) {
            Log.i("HyprLuaReserve", "worker exit pid=${Process.myPid()} cpuMs=${Process.getElapsedCpuTime()}")
        }
        Process.killProcess(Process.myPid())
    }
}
