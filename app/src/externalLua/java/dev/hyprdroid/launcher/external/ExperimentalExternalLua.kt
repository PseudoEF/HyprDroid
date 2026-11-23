package dev.hyprdroid.launcher.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileRequest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object ExperimentalExternalLua {
    private val workers = Semaphore(2)
    @Volatile private var uncertainDeath = false

    suspend fun run(context: Context, source: ByteArray, request: TileRequest): PluginResult = workers.withPermit {
        if (!BuildConfig.EXTERNAL_LUA || uncertainDeath) return@withPermit PluginResult.Failure("Внешний Lua недоступен")
        val event = when (request.event) {
            null -> 0
            "increment" -> 1
            "reset" -> 2
            else -> return@withPermit PluginResult.Failure("Недопустимое событие Lua")
        }
        val state = request.state.encodeToByteArray()
        if (source.size !in 1..16384 || state.size > 512) {
            return@withPermit PluginResult.Failure("Превышен размер Lua-запроса")
        }
        withContext(Dispatchers.Main.immediate) { bind(context.applicationContext, source, state, event) }
    }

    private suspend fun bind(app: Context, source: ByteArray, state: ByteArray, event: Int): PluginResult {
        val uuid = UUID.randomUUID()
        val id = uuid.mostSignificantBits.takeUnless { it == 0L } ?: 1L
        val connected = CompletableDeferred<IExternalLua?>()
        val outcome = CompletableDeferred<PluginResult>()
        val died = CompletableDeferred<Unit>()
        val closing = AtomicBoolean(false)
        Log.d("HyprLuaTile", "Worker binding requested")
        val death = IBinder.DeathRecipient {
            Log.d("HyprLuaTile", "Worker Binder died; disposalRequested=${closing.get()}")
            died.complete(Unit)
            outcome.complete(PluginResult.Failure("Изолированный Lua worker завершился"))
        }
        val receiver = object : IExternalLuaReply.Stub() {
            private fun valid(session: Long, number: Int) =
                Binder.getCallingUid() >= 0 && Binder.getCallingUid() != Process.myUid() &&
                    session == id && number == 1 && !outcome.isCompleted

            override fun render(session: Long, number: Int, frame: SharedMemory) {
                if (!valid(session, number)) {
                    frame.close()
                    return
                }
                val result = try {
                    decodeFrame(checkedBytes(frame, 16385))
                } catch (_: Exception) {
                    PluginResult.Failure("Некорректный кадр внешнего Lua")
                }
                outcome.complete(result)
            }

            override fun error(session: Long, number: Int, code: Int) {
                if (valid(session, number)) outcome.complete(PluginResult.Failure(when (code) {
                    1 -> "Исчерпана память Lua VM"
                    2 -> "Lua завершился с ошибкой или превысил квоту"
                    else -> "Недопустимый Lua-запрос"
                }))
            }
        }
        var endpoint: IExternalLua? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try {
                    service.linkToDeath(death, 0)
                    val port = IExternalLua.Stub.asInterface(service)
                    endpoint = port
                    connected.complete(port)
                } catch (_: android.os.RemoteException) {
                    died.complete(Unit)
                    connected.complete(null)
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                outcome.complete(PluginResult.Failure("Связь с Lua worker потеряна"))
            }
            override fun onBindingDied(name: ComponentName) {
                connected.complete(null)
                onServiceDisconnected(name)
            }
            override fun onNullBinding(name: ComponentName) {
                connected.complete(null)
            }
        }
        var bound = false
        val result = try {
            bound = app.bindIsolatedService(
                Intent(app, ExternalLuaService::class.java), Context.BIND_AUTO_CREATE,
                "ext_${uuid.toString().replace('-', '_')}", app.mainExecutor, connection,
            )
            if (!bound) return PluginResult.Failure("Android не запустил внешний Lua worker")
            withTimeoutOrNull(5_000) {
                val port = connected.await() ?: return@withTimeoutOrNull PluginResult.Failure("Lua worker недоступен")
                sealedBytes(source).use { entry ->
                    sealedBytes(state).use { saved -> port.createSession(id, 1, entry, saved, receiver) }
                }
                port.dispatchEvent(id, 1, event)
                outcome.await()
            } ?: PluginResult.Failure("Внешний Lua не ответил вовремя")
        } catch (_: android.os.RemoteException) {
            PluginResult.Failure("Ошибка Binder внешнего Lua")
        } catch (_: SecurityException) {
            PluginResult.Failure("Android отклонил запуск внешнего Lua")
        } finally {
            closing.set(true)
            Log.d("HyprLuaTile", "Worker binding closing")
            val port = endpoint
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
                if (bound && withTimeoutOrNull(2_000) { died.await(); true } != true) {
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
        return if (uncertainDeath) PluginResult.Failure("Остановка Lua worker не подтверждена") else result
    }
}
