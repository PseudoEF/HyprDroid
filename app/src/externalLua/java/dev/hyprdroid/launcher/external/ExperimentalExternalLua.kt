package dev.hyprdroid.launcher.external

import android.content.Context
import android.os.Binder
import android.os.Process
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileRequest
import dev.hyprdroid.launcher.plugins.TileSignal
import dev.hyprdroid.launcher.plugins.TileExecutionV2
import dev.hyprdroid.launcher.plugins.TileFrameV2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object ExperimentalExternalLua {
    private val workers = WorkerAdmission(active = 2, pending = 4, waitMillis = 5_000)
    @Volatile private var uncertainDeath = false

    private val processes = Semaphore(2)
    // Instrumentation switches the policy before starting an identically configured run.
    @Volatile internal var prewarmExperiment = false
    private val reserve = PrewarmedWorker(processes, workers::hasDemand,
        healthy = { !uncertainDeath && !excessiveFailures() },
        unconfirmedExit = ::blockAfterUnconfirmedExit,
        unexpectedExit = ::recordUnexpectedExit)

    fun visibleTile(context: Context): () -> Unit =
        if (BuildConfig.V2_FIXTURE_LUA && prewarmExperiment) reserve.visibleTile(context.applicationContext) else ({})

    private fun blockAfterUnconfirmedExit() {
        uncertainDeath = true
        reserve.discard()
        Log.w("HyprLuaTile", "Worker death unconfirmed; new bindings blocked")
    }

    private fun recordUnexpectedExit() {
        if (excessiveFailures(record = true)) reserve.discard()
    }

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
        return execute(context, source, state, event, null, true, dispatch, authorized,
            rejected = {
                if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaTile", "Worker admission declined")
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
    ): TileFrameV2? {
        val wire = encodeRequestV2(request)
        return execute<TileFrameV2?>(context, source, wire.context, 0, wire, request.interaction != null || request.signal is TileSignal.Resume, dispatch, authorized,
            rejected = {
                if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaTile", "Worker admission declined")
                throw ExternalLuaBusy()
            }, failure = { null },
        ) {
            decodeFrameV2(it, wire.capabilities)
        }
    }

    private suspend fun <T> execute(
        context: Context, source: ByteArray, state: ByteArray, event: Int, v2: WireRequestV2?,
        prewarmAfter: Boolean, dispatch: ((() -> Unit) -> Boolean)?, authorized: () -> Boolean, rejected: () -> T, failure: (String) -> T, decode: (ByteArray) -> T,
    ): T {
        val queuedAt = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
        val result = workers.execute(rejected) admitted@{
            val admittedAt = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
            if (!BuildConfig.EXTERNAL_LUA || uncertainDeath || excessiveFailures() || !authorized()) {
                return@admitted failure("Внешний Lua недоступен")
            }
            if (source.size !in 1..TILE_SOURCE_LIMIT) return@admitted failure("Превышен размер Lua-запроса")
            withContext(Dispatchers.IO) {
                bind(context.applicationContext, source, state, event, v2, dispatch, authorized, failure, decode, queuedAt, admittedAt)
            }
        }
        if (BuildConfig.V2_FIXTURE_LUA && prewarmExperiment && prewarmAfter && authorized()) {
            reserve.prepare(context.applicationContext)
        }
        return result
    }

    private suspend fun <T> bind(
        app: Context, source: ByteArray, state: ByteArray, event: Int, v2: WireRequestV2?,
        dispatch: ((() -> Unit) -> Boolean)?, authorized: () -> Boolean, failure: (String) -> T, decode: (ByteArray) -> T,
        queuedAt: Long, admittedAt: Long,
    ): T {
        if (!authorized()) return failure("Доверие к модулю отозвано")
        val started = SystemClock.elapsedRealtime()
        val warm = if (BuildConfig.V2_FIXTURE_LUA && prewarmExperiment) reserve.take() else null
        var permit = warm != null
        var binding: IsolatedWorkerBinding? = warm
        var assigned = false
        val result = try {
            if (warm == null) {
                if (withTimeoutOrNull(5_000) { processes.acquire(); permit = true; true } != true) {
                    return failure("Внешний Lua занят")
                }
                if (uncertainDeath || !authorized()) return failure("Внешний Lua недоступен")
                binding = IsolatedWorkerBinding(app)
                if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaReserve", "reserve=cold")
                if (!binding.start()) return failure("Android не запустил внешний Lua worker")
            }
            dispatchToWorker(
                checkNotNull(binding), source, state, event, v2, dispatch, authorized, failure, decode,
                queuedAt, admittedAt, 5_000 - (SystemClock.elapsedRealtime() - started),
                onAssigned = { assigned = true },
            )
        } catch (_: android.os.RemoteException) {
            return failure("Ошибка Binder внешнего Lua")
        } catch (_: android.system.ErrnoException) {
            return failure("Ошибка памяти IPC внешнего Lua")
        } catch (_: SecurityException) {
            return failure("Android отклонил запуск внешнего Lua")
        } finally {
            if (binding?.close(assigned) == false) blockAfterUnconfirmedExit()
            if (binding?.failedUnexpectedly() == true) recordUnexpectedExit()
            if (permit) processes.release()
            if (BuildConfig.V2_FIXTURE_LUA && binding != null) {
                Log.i("HyprLuaGate", "operation=${binding.id} phase=released sinceQueueMs=${SystemClock.elapsedRealtime() - queuedAt}")
            }
        }
        return if (uncertainDeath) failure("Остановка Lua worker не подтверждена") else result
    }

    private suspend fun <T> dispatchToWorker(
        binding: IsolatedWorkerBinding, source: ByteArray, state: ByteArray, event: Int, v2: WireRequestV2?,
        dispatch: ((() -> Unit) -> Boolean)?, authorized: () -> Boolean, failure: (String) -> T, decode: (ByteArray) -> T,
        queuedAt: Long, admittedAt: Long, remaining: Long, onAssigned: () -> Unit,
    ): T {
        val id = binding.id
        val bindingAt = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
        fun trace(phase: String) {
            if (BuildConfig.V2_FIXTURE_LUA) {
                Log.i("HyprLuaGate", "operation=$id phase=$phase sinceQueueMs=${SystemClock.elapsedRealtime() - queuedAt} queueMs=${admittedAt - queuedAt} ioMs=${bindingAt - admittedAt}")
            }
        }
        trace("binding")
        val outcome = CompletableDeferred<T>()
        val replyClaimed = AtomicBoolean(false)
        val receiver = object : IExternalLuaReply.Stub() {
            private fun valid(session: Long, number: Int) =
                Binder.getCallingUid() >= 0 && Binder.getCallingUid() != Process.myUid() &&
                    session == id && number == 1 && authorized() && binding.acceptsReply() && !outcome.isCompleted &&
                    replyClaimed.compareAndSet(false, true)

            override fun render(session: Long, number: Int, frame: SharedMemory?) {
                if (!valid(session, number)) {
                    frame?.close()
                    return
                }
                trace("reply")
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
        return withTimeoutOrNull(remaining) {
            val port = binding.connected.await() ?: return@withTimeoutOrNull failure("Lua worker недоступен")
            trace("connected")
            if (!authorized() || uncertainDeath || binding.disconnected.isCompleted) {
                return@withTimeoutOrNull failure("Доверие к модулю отозвано или worker завершился")
            }
            sealedBytes(source).use { entry ->
                sealedBytes(state).use { saved ->
                    val send = {
                        onAssigned()
                        if (v2 == null) port.createSession(id, 1, entry, saved, receiver)
                        else port.createSessionV2(id, TILE_WIRE_V2, v2.capabilities, v2.event, entry, saved, receiver)
                        trace("dispatch")
                        port.dispatchEvent(id, 1, event)
                    }
                    if (!authorized()) return@withTimeoutOrNull failure("Доверие к модулю отозвано")
                    if (dispatch == null) send()
                    else if (!dispatch(send)) return@withTimeoutOrNull failure("Доверие к модулю отозвано")
                }
            }
            select {
                outcome.onAwait { it }
                binding.disconnected.onAwait { failure("Изолированный Lua worker завершился") }
            }
        } ?: run {
            if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaTile", "Worker response timed out")
            failure("Внешний Lua не ответил вовремя")
        }
    }
}
