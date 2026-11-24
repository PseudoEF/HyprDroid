package dev.hyprdroid.launcher.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Owns a single Android binding. A binding can be assigned at most one Lua request. */
internal class IsolatedWorkerBinding(private val app: Context) {
    private val uuid = UUID.randomUUID()
    val id = uuid.mostSignificantBits.takeUnless { it == 0L } ?: 1L
    val connected = CompletableDeferred<IExternalLua?>()
    val disconnected = CompletableDeferred<Unit>()
    private val died = CompletableDeferred<Unit>()
    private val lock = Any()
    private var endpoint: IExternalLua? = null
    private var closing = false
    private var bound = false

    private val death = IBinder.DeathRecipient {
        val expected = synchronized(lock) { closing }
        Log.d("HyprLuaTile", "Worker Binder died; disposalRequested=$expected")
        died.complete(Unit)
        disconnected.complete(Unit)
        connected.complete(null)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lock) {
                if (closing || endpoint != null || disconnected.isCompleted) return
                try {
                    service.linkToDeath(death, 0)
                    val port = IExternalLua.Stub.asInterface(service)
                    endpoint = port
                    connected.complete(port)
                } catch (_: RemoteException) {
                    death.binderDied()
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            disconnected.complete(Unit)
            connected.complete(null)
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) {
            connected.complete(null)
        }
    }

    fun start(): Boolean {
        Log.d("HyprLuaTile", "Worker binding requested")
        // Mark the attempt so Android can release a connection registered before a failure.
        bound = true
        return app.bindIsolatedService(
            Intent(app, ExternalLuaService::class.java), Context.BIND_AUTO_CREATE,
            "ext_${uuid.toString().replace('-', '_')}", app.mainExecutor, connection,
        )
    }

    suspend fun close(assigned: Boolean): Boolean = withContext(NonCancellable) {
        val port = synchronized(lock) {
            closing = true
            endpoint
        }
        Log.d("HyprLuaTile", "Worker binding closing")
        if (assigned) {
            try {
                port?.disposeSession(id)
            } catch (_: RemoteException) {
                // Binder death is confirmed below.
            }
        }
        if (bound) {
            try {
                app.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Binding never registered.
            }
        }
        // Without a published Binder, no data or Lua request could reach this process.
        val confirmed = port == null || withTimeoutOrNull(2_000) { died.await(); true } == true
        try {
            port?.asBinder()?.unlinkToDeath(death, 0)
        } catch (_: NoSuchElementException) {
            // The kernel already removed the notification.
        }
        confirmed
    }
}
