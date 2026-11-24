package dev.hyprdroid.launcher.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalLuaProtocolTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test
    fun foreignIdentifiersAndReplayedEventsCannotConsumeTheOwningReply() = runBlocking {
        withWorker { worker, died ->
            val result = CompletableDeferred<String>()
            val replies = AtomicInteger()
            val reply = object : IExternalLuaReply.Stub() {
                override fun render(id: Long, request: Int, frame: SharedMemory) {
                    replies.incrementAndGet()
                    result.complete(frame.use { decodeFrame(checkedBytes(it, 16_385)).tile?.state.orEmpty() })
                }
                override fun error(id: Long, request: Int, code: Int) {
                    replies.incrementAndGet()
                    result.completeExceptionally(AssertionError("Unexpected worker rejection"))
                }
            }
            val sample = instrumentation.context.assets.open("external-hello-1.0.0.lua").use { it.readBytes() }
            sealedBytes(sample).use { source ->
                sealedBytes("7".encodeToByteArray()).use { state ->
                    worker.createSession(0, 1, source, state, reply)
                    worker.createSession(17, 1, source, state, reply)
                    worker.createSession(18, 1, source, state, reply)
                }
            }
            worker.disposeSession(18)
            worker.dispatchEvent(18, 1, 2)
            worker.dispatchEvent(17, 3, 1)
            worker.dispatchEvent(17, 1, 3)
            val binder = worker.asBinder()
            val descriptor = requireNotNull(binder.interfaceDescriptor)
            val dispatchCode = IExternalLua.Stub.TRANSACTION_dispatchEvent
            // None of these malformed wire messages may consume the reply or close the session.
            for (token in listOf("invalid.test.descriptor", descriptor)) {
                sendMalformed(binder, dispatchCode, token) {
                    writeLong(17)
                    writeInt(1)
                    writeInt(2)
                    writeInt(123)
                }
            }
            sendMalformed(binder, dispatchCode, descriptor) {
                writeLong(17)
                writeInt(1)
            }
            sendMalformed(binder, IExternalLua.Stub.TRANSACTION_disposeSession, descriptor) {
                writeLong(17)
                writeInt(123)
            }
            worker.dispatchEvent(17, 1, 1)
            worker.dispatchEvent(17, 1, 1)
            assertEquals("8", withTimeout(5_000) { result.await() })
            worker.disposeSession(17)
            withTimeout(5_000) { died.await() }
            assertEquals(1, replies.get())
            assertThrows(RemoteException::class.java) { worker.dispatchEvent(17, 1, 1) }
        }
    }

    @Test
    fun oversizedSharedMemoryIsRejectedBeforeMappingAndWorkerExits() = runBlocking {
        withWorker { worker, died ->
            val reply = object : IExternalLuaReply.Stub() {
                override fun render(id: Long, request: Int, frame: SharedMemory) = frame.close()
                override fun error(id: Long, request: Int, code: Int) = Unit
            }
            SharedMemory.create(null, 2 * 1024 * 1024).use { source ->
                sealedBytes(byteArrayOf()).use { state -> worker.createSession(17, 1, source, state, reply) }
            }
            assertNotNull(withTimeout(5_000) { died.await() })
        }
    }

    private fun sendMalformed(binder: IBinder, code: Int, token: String, write: Parcel.() -> Unit) {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInterfaceToken(token)
            parcel.write()
            assertEquals(true, binder.transact(code, parcel, null, IBinder.FLAG_ONEWAY))
        } finally {
            parcel.recycle()
        }
    }

    private suspend fun withWorker(test: suspend (IExternalLua, CompletableDeferred<Unit>) -> Unit) {
        val connectionReady = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connectionReady.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val intent = Intent(app, ExternalLuaService::class.java)
        check(app.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, "protocol_${UUID.randomUUID().toString().replace('-', '_')}",
            app.mainExecutor, connection))
        try {
            val binder = withTimeout(5_000) { connectionReady.await() }
            val died = CompletableDeferred<Unit>()
            binder.linkToDeath({ died.complete(Unit) }, 0)
            test(IExternalLua.Stub.asInterface(binder), died)
        } finally {
            app.unbindService(connection)
        }
    }
}
