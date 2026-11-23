package dev.hyprdroid.launcher.plugins

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import java.io.IOException
import java.util.concurrent.Executors

/** Private Android isolated service with no access to the package manager's storage. */
class LuaPluginService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var received = false
    private val endpoint by lazy { Messenger(Handler(Looper.getMainLooper(), ::receive)) }

    override fun onBind(intent: Intent): IBinder = endpoint.binder

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun receive(message: Message): Boolean {
        if (received || message.what != LuaPluginIpc.RUN || message.arg1 != LuaPluginIpc.VERSION) return true
        if (message.sendingUid != applicationInfo.uid || message.sendingUid == Process.myUid()) return true
        val reply = message.replyTo ?: return true
        received = true

        val data = message.data
        val plugin = BuiltInLuaPlugin.findRuntime(data.getString(LuaPluginIpc.ID).orEmpty())
        if (plugin == null) {
            send(reply, PluginResult.Failure("Lua-модуль не входит в доверенный список"))
            return true
        }
        val inputs = try {
            LuaPluginIpc.readInputs(data, plugin)
        } catch (_: RuntimeException) {
            send(reply, PluginResult.Failure("Некорректный запрос к Lua Runtime"))
            return true
        }

        val tile = try {
            LuaPluginIpc.readTileRequest(data, plugin)
        } catch (_: RuntimeException) {
            send(reply, PluginResult.Failure("Некорректное событие тайла"))
            return true
        }

        worker.execute {
            val result = try {
                TrustedLuaRuntime().run(readAsset(plugin), plugin.permissions, inputs, tile)
            } catch (_: IOException) {
                PluginResult.Failure("Не удалось прочитать встроенный Lua-модуль")
            } catch (_: RuntimeException) {
                PluginResult.Failure("Внутренняя ошибка Lua Runtime")
            }
            send(reply, result)
        }
        return true
    }

    private fun readAsset(plugin: BuiltInLuaPlugin): ByteArray = assets.open(plugin.asset).use { input ->
        val buffer = ByteArray(PluginLimits.SOURCE_BYTES + 1)
        var length = 0
        while (length < buffer.size) {
            val count = input.read(buffer, length, buffer.size - length)
            if (count < 0) break
            length += count
        }
        buffer.copyOf(length)
    }

    private fun send(reply: Messenger, result: PluginResult) {
        val message = Message.obtain(null, LuaPluginIpc.RESULT, LuaPluginIpc.VERSION, 0)
        message.data = LuaPluginIpc.response(result)
        try {
            reply.send(message)
        } catch (_: RemoteException) {
            // The terminal may have closed before the result was ready.
        }
    }
}
