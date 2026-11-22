package dev.hyprdroid.launcher.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object AndroidLuaPlugins {
    private const val IPC_TIMEOUT_MS = 5_000L
    private val executionLock = Mutex()

    suspend fun run(context: Context, plugin: BuiltInLuaPlugin): PluginResult = executionLock.withLock {
        val app = context.applicationContext
        val inputs = withContext(Dispatchers.IO) { readInputs(app, plugin.permissions) }
        withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(IPC_TIMEOUT_MS) { runIsolated(app, plugin, inputs) }
                ?: PluginResult.Failure("Изолированный процесс Lua не ответил вовремя")
        }
    }

    private suspend fun runIsolated(
        app: Context,
        plugin: BuiltInLuaPlugin,
        inputs: PluginInputs,
    ): PluginResult {
        val result = CompletableDeferred<PluginResult>()
        val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (!result.isCompleted && message.what == LuaPluginIpc.RESULT && message.arg1 == LuaPluginIpc.VERSION) {
                if (message.sendingUid < 0 || message.sendingUid == Process.myUid()) {
                    result.complete(PluginResult.Failure("Ответ Lua получен вне изолированного процесса"))
                } else {
                    result.complete(LuaPluginIpc.readResult(message.data, plugin))
                }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (result.isCompleted) return
                val request = Message.obtain(null, LuaPluginIpc.RUN, LuaPluginIpc.VERSION, 0)
                request.replyTo = receiver
                request.data = LuaPluginIpc.request(plugin, inputs)
                try {
                    Messenger(binder).send(request)
                } catch (_: RemoteException) {
                    disconnected()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = disconnected()
            override fun onBindingDied(name: ComponentName) = disconnected()
            override fun onNullBinding(name: ComponentName) = disconnected()

            private fun disconnected() {
                result.complete(PluginResult.Failure("Изолированный процесс Lua недоступен"))
            }
        }
        try {
            val intent = Intent(app, LuaPluginService::class.java)
            val instance = "lua_" + UUID.randomUUID().toString().replace('-', '_')
            if (!app.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, instance, app.mainExecutor, connection)) {
                return PluginResult.Failure("Android не запустил изолированный процесс Lua")
            }
            return result.await()
        } catch (_: SecurityException) {
            return PluginResult.Failure("Android запретил подключение к процессу Lua")
        } catch (_: IllegalArgumentException) {
            return PluginResult.Failure("Android отклонил подключение к процессу Lua")
        } finally {
            result.cancel()
            try {
                app.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // The system may have refused the binding before registering a connection.
            }
        }
    }

    private fun readInputs(context: Context, permissions: Set<PluginPermission>): PluginInputs {
        val device = if (PluginPermission.SYSTEM_DEVICE in permissions) {
            PluginDevice(
                Build.MANUFACTURER.deviceField(),
                Build.MODEL.deviceField(),
                Build.VERSION.RELEASE.deviceField(),
                Build.VERSION.SDK_INT,
            )
        } else {
            null
        }
        val battery = if (PluginPermission.SYSTEM_BATTERY in permissions) {
            try {
                val manager = context.getSystemService(BatteryManager::class.java)
                PluginBattery(
                    manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
                    manager?.isCharging,
                )
            } catch (_: SecurityException) {
                PluginBattery(null, null)
            }
        } else {
            null
        }
        return PluginInputs(device, battery)
    }

    private fun String?.deviceField() =
        orEmpty().filterNot(Char::isISOControl).trim().take(PluginLimits.DEVICE_FIELD).ifEmpty { "?" }
}
