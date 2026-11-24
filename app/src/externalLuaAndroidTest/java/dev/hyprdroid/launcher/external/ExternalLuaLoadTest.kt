package dev.hyprdroid.launcher.external

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalLuaLoadTest {
    @Test fun repeatedWorkerBurstsPreserveIndependentResults() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val source = instrumentation.context.assets.open("external-hello-1.0.0.lua").use { it.readBytes() }
        val startedAt = SystemClock.elapsedRealtime()
        val latencies = mutableListOf<Long>()
        repeat(10) { round ->
            val results = (0..5).map { offset ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    val initial = round * 6 + offset
                    val started = SystemClock.elapsedRealtime()
                    val result = ExperimentalExternalLua.run(app, source, TileRequest("increment", initial.toString()))
                    val success = result as? PluginResult.Success ?: error("Request $initial failed: $result")
                    assertEquals((initial + 1).toString(), success.tile?.state)
                    SystemClock.elapsedRealtime() - started
                }
            }.awaitAll()
            latencies += results
        }
        val sorted = latencies.sorted()
        Log.i("HyprLuaLoad", "rounds=10 burst=6 completed=${sorted.size} " +
            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} p95Ms=${sorted[56]} maxMs=${sorted.last()}")
    }
}
