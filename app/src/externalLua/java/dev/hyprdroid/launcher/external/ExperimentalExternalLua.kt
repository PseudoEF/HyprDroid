package dev.hyprdroid.launcher.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileRequest
import dev.hyprdroid.launcher.plugins.TileSignal
import dev.hyprdroid.launcher.plugins.TileExecutionV2
import dev.hyprdroid.launcher.plugins.TileFrameV2
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object ExperimentalExternalLua {
    private val workers = WorkerAdmission(active = 2, pending = 4, waitMillis = 5_000)
    @Volatile private var uncertainDeath = false

    private val failures = ArrayDeque<Long>()

    private fun excessiveFailures(record: Boolean = false): Boolean = synchronized(failures) {
        val now = SystemClock.elapsedRealtime()
        while (failures.firstOrNull()?.let { now - it >= 10_000 } == true) failures.removeFirst()
        if (record && failures.size < 4) failures.addLast(now)
        failures.size >= 4
    }

    suspend fun run(
        context: Context,
        source: ByteArray,
        request: TileRequest,
        dispatch: ((() -> Unit) -> Boolean)? = null,
        authorized: () -> Boolean = { true },
    ): PluginResult {
        val event = when (request.event) {
            null -> 0
            "increment" -> 1
            "reset" -> 2
            else -> return PluginResult.Failure("Недопустимое событие Lua")
        }
        val state = request.state.encodeToByteArray()
        if (state.size > 512) return PluginResult.Failure("Превышен размер Lua-запроса")
        return execute(context, source, state, event, null, dispatch, authorized,
            priority = if (request.event == null) WorkerAdmission.Priority.NORMAL else WorkerAdmission.Priority.USER,
            rejected = {
                if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaTile", "Worker admission declined priority=${if (request.event == null) "normal" else "user"}")
                PluginResult.Failure("Внешний Lua занят")
            },
            failure = { PluginResult.Failure(it) },
            decode = ::decodeFrame,
        )
    }

    suspend fun runV2(
        context: Context,
        source: ByteArray,
        request: TileExecutionV2,
        dispatch: ((() -> Unit) -> Boolean)? = null,
        authorized: () -> Boolean = { true },
        inputTrace: String? = null,
    ): TileFrameV2? {
        val wire = encodeRequestV2(request)
        return execute<TileFrameV2?>(context, source, wire.context, 0, wire, dispatch, authorized,
            priority = when {
                request.interaction != null -> WorkerAdmission.Priority.USER
                request.signal is TileSignal.Timer -> WorkerAdmission.Priority.TIMER
                else -> WorkerAdmission.Priority.NORMAL
            },
            inputTrace = inputTrace,
            rejected = {
                if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaTile", "Worker admission declined interaction=${request.interaction != null} signal=${request.signal?.javaClass?.simpleName}")
                throw ExternalLuaBusy()
            }, failure = { null },
        ) {
            decodeFrameV2(it, wire.capabilities)
        }
    }

    private suspend fun <T> execute(
        context: Context, source: ByteArray, state: ByteArray, event: Int, v2: WireRequestV2?,
        dispatch: ((() -> Unit) -> Boolean)?, authorized: () -> Boolean, priority: WorkerAdmission.Priority, inputTrace: String? = null, rejected: () -> T, failure: (String) -> T, decode: (ByteArray) -> T,
    ): T {
        val queuedAt = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
        TileInputTrace.stage(inputTrace, "queue-submitted")
        return workers.execute(rejected, priority) admitted@{
            TileInputTrace.stage(inputTrace, "queue-admitted")
            val admittedAt = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
            if (!BuildConfig.EXTERNAL_LUA || uncertainDeath || excessiveFailures() || !authorized()) {
                return@admitted failure("Внешний Lua недоступен")
            }
            if (source.size !in 1..TILE_SOURCE_LIMIT) return@admitted failure("Превышен размер Lua-запроса")
            withContext(Dispatchers.IO) {
                bind(context.applicationContext, source, state, event, v2, dispatch, authorized, failure, decode, queuedAt, admittedAt, inputTrace)
            }
        }
    }

    private suspend fun <T> bind(
        app: Context, source: ByteArray, state: ByteArray, event: Int, v2: WireRequestV2?,
        dispatch: ((() -> Unit) -> Boolean)?, authorized: () -> Boolean, failure: (String) -> T, decode: (ByteArray) -> T,
        queuedAt: Long, admittedAt: Long, inputTrace: String?,
    ): T {
        val uuid = UUID.randomUUID()
        val id = uuid.mostSignificantBits.takeUnless { it == 0L } ?: 1L
        val bindingAt = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
        fun trace(phase: String) {
            if (BuildConfig.V2_FIXTURE_LUA) {
                Log.i("HyprLuaGate", "operation=$id phase=$phase sinceQueueMs=${SystemClock.elapsedRealtime() - queuedAt} queueMs=${admittedAt - queuedAt} ioMs=${bindingAt - admittedAt}")
            }
        }
        trace("binding")
        TileInputTrace.stage(inputTrace, "worker-bound-request worker=$id")
        val connected = CompletableDeferred<IExternalLua?>()
        val outcome = CompletableDeferred<T>()
        val died = CompletableDeferred<Unit>()
        val closing = AtomicBoolean(false)
        val replyClaimed = AtomicBoolean(false)
        val failureRecorded = AtomicBoolean(false)
        fun recordUnexpectedDeath() {
            if (!closing.get() && failureRecorded.compareAndSet(false, true)) excessiveFailures(record = true)
        }
        Log.d("HyprLuaTile", "Worker binding requested")
        val death = IBinder.DeathRecipient {
            Log.d("HyprLuaTile", "Worker Binder died; disposalRequested=${closing.get()}")
            recordUnexpectedDeath()
            died.complete(Unit)
            outcome.complete(failure("Изолированный Lua worker завершился"))
        }
        val receiver = object : IExternalLuaReply.Stub() {
            private fun valid(session: Long, number: Int) =
                Binder.getCallingUid() >= 0 && Binder.getCallingUid() != Process.myUid() &&
                    session == id && number == 1 && authorized() && !closing.get() && !outcome.isCompleted &&
                    replyClaimed.compareAndSet(false, true)

            override fun render(session: Long, number: Int, frame: SharedMemory?) {
                if (!valid(session, number)) {
                    frame?.close()
                    if (BuildConfig.V2_FIXTURE_LUA && session == id && number == 1 && !authorized()) {
                        Log.i("HyprLuaGate", "Discarded revoked worker frame")
                    }
                    return
                }
                trace("reply")
                TileInputTrace.stage(inputTrace, "binder-reply worker=$id")
                val result = try {
                    requireNotNull(frame).use { decode(checkedBytes(it, TILE_FRAME_LIMIT)) }
                } catch (_: Exception) {
                    failure("Некорректный кадр внешнего Lua")
                }
                outcome.complete(result)
            }

            override fun error(session: Long, number: Int, code: Int) {
                if (!valid(session, number)) return
                trace("error")
                outcome.complete(failure(when (code) {
                    1 -> "Исчерпана память Lua VM"
                    2 -> "Lua завершился с ошибкой или превысил квоту"
                    else -> "Недопустимый Lua-запрос"
                }))
            }
        }
        val endpoint = AtomicReference<IExternalLua?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closing.get()) return
                try {
                    service.linkToDeath(death, 0)
                    val port = IExternalLua.Stub.asInterface(service)
                    endpoint.set(port)
                    trace("connected")
                    connected.complete(port)
                } catch (_: android.os.RemoteException) {
                    recordUnexpectedDeath()
                    died.complete(Unit)
                    connected.complete(null)
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                recordUnexpectedDeath()
                outcome.complete(failure("Связь с Lua worker потеряна"))
            }
            override fun onBindingDied(name: ComponentName) {
                connected.complete(null)
                onServiceDisconnected(name)
            }
            override fun onNullBinding(name: ComponentName) {
                connected.complete(null)
            }
        }
        val result = try {
            if (!authorized()) return failure("Доверие к модулю отозвано")
            val bound = app.bindIsolatedService(
                Intent(app, ExternalLuaService::class.java), Context.BIND_AUTO_CREATE,
                "ext_${uuid.toString().replace('-', '_')}", app.mainExecutor, connection,
            )
            if (!bound) return failure("Android не запустил внешний Lua worker")
            withTimeoutOrNull(5_000) {
                val port = connected.await() ?: return@withTimeoutOrNull failure("Lua worker недоступен")
                if (!authorized()) return@withTimeoutOrNull failure("Доверие к модулю отозвано")
                sealedBytes(source).use { entry ->
                    sealedBytes(state).use { saved ->
                        if (v2 == null) port.createSession(id, 1, entry, saved, receiver)
                        else port.createSessionV2(id, TILE_WIRE_V2, v2.capabilities, v2.event, entry, saved, receiver)
                    }
                }
                if (!authorized()) return@withTimeoutOrNull failure("Доверие к модулю отозвано")
                val send = {
                    trace("dispatch")
                    TileInputTrace.stage(inputTrace, "binder-dispatch worker=$id")
                    port.dispatchEvent(id, 1, event)
                }
                if (dispatch == null) send()
                else if (!dispatch(send)) return@withTimeoutOrNull failure("Доверие к модулю отозвано")
                outcome.await()
            } ?: run {
                if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaTile", "Worker response timed out")
                failure("Внешний Lua не ответил вовремя")
            }
        } catch (_: android.os.RemoteException) {
            failure("Ошибка Binder внешнего Lua")
        } catch (_: android.system.ErrnoException) {
            failure("Ошибка памяти IPC внешнего Lua")
        } catch (_: SecurityException) {
            failure("Android отклонил запуск внешнего Lua")
        } finally {
            closing.set(true)
            Log.d("HyprLuaTile", "Worker binding closing")
            val port = endpoint.get()
            withContext(NonCancellable) {
                try {
                    port?.disposeSession(id)
                } catch (_: android.os.RemoteException) {
                    // Binder death is confirmed separately.
                }
                try {
                    app.unbindService(connection)
                } catch (_: IllegalArgumentException) {
                    // The system may have rejected the bind before registering it.
                }
                // No Lua can start before the callback publishes a Binder. Revoking a
                // pending bind must not block all future verified sessions.
                if (port != null && withTimeoutOrNull(2_000) { died.await(); true } != true) {
                    uncertainDeath = true
                    Log.w("HyprLuaTile", "Worker death unconfirmed; new bindings blocked")
                }
                try {
                    port?.asBinder()?.unlinkToDeath(death, 0)
                } catch (_: NoSuchElementException) {
                    // The kernel already removed the notification.
                }
            }
        }
        trace("released")
        return if (uncertainDeath) failure("Остановка Lua worker не подтверждена") else result
    }
}
