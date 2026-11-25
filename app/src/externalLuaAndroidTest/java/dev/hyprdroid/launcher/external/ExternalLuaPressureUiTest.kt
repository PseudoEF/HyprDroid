package dev.hyprdroid.launcher.external

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.MainActivity
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.model.ShellPresentation
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.workspaceengine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalLuaPressureUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun eightClocksRecoverAndReportRejectedInput() {
        check(BuildConfig.V2_FIXTURE_LUA)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val count = 8
        val app = instrumentation.targetContext
        val ids = List(count) { WindowId("bench-clock-$it") }
        val store = WorkspaceEngineStore(app)
        runBlocking {
            if (count > 0) AndroidPackages.manager(app).install("clock-tile")
            ShellRepository(app).setPresentation(ShellPresentation())
            WorkspaceRepository(app).selectWorkspace(0)
            val engine = WorkspaceEngine()
            for ((index, id) in ids.withIndex()) {
                val window = WorkspaceWindow(id, "clock-tile", 0, 0)
                if (index == 0) check(engine.addFirstWindow(window))
                else check(engine.splitWindow(ids[(index - 1) / 2], Orientation.VERTICAL, SplitId("bench-$index"), window))
            }
            store.save("0", engine, open = count > 0)
            store.save("1", WorkspaceEngine(), open = false)
        }
        val latencies = mutableListOf<Long>()
        val readyWaits = mutableListOf<Long>()
        var refusals = 0
        var initialFrames = 0
        var numAttempts = 0
        val start = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(20_000) {
                    initialFrames = ids.count { id -> runCatching { toggle(id).assertExists() }.isSuccess }
                    initialFrames == count
                }
                Log.i("HyprLuaGate", "pressure firstFrames=$initialFrames elapsedMs=${SystemClock.elapsedRealtime() - start} corePssKiB=${android.os.Debug.getPss()}")
                for (round in 0..5) {
                    if (round == 2) {
                        selectWorkspace(2)
                        scenario.moveToState(Lifecycle.State.CREATED)
                        SystemClock.sleep(3_000)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        selectWorkspace(1)
                        compose.waitUntil(20_000) {
                            ids.all { id -> runCatching { toggle(id).assertExists() }.isSuccess }
                        }
                    }
                    if (ids.isNotEmpty()) {
                        val id = ids[round % ids.size]
                        numAttempts++
                        val waitingAt = SystemClock.elapsedRealtime()
                        compose.waitUntil(5_000) { available(id) }
                        val node = toggle(id)
                        val previous = requireNotNull(node.fetchSemanticsNode().config.getOrNull(
                            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState))
                        val clickAt = SystemClock.elapsedRealtime()
                        readyWaits += clickAt - waitingAt
                        node.performClick()
                        var changed = false
                        compose.waitUntil(7_000) {
                            changed = runCatching { toggle(id).fetchSemanticsNode().config.getOrNull(
                                androidx.compose.ui.semantics.SemanticsProperties.ToggleableState) != previous
                            }.getOrDefault(false)
                            changed || refusalVisible(id)
                        }
                        if (changed) latencies += SystemClock.elapsedRealtime() - clickAt else refusals++
                    }
                    SystemClock.sleep(2_000)
                }
                // Observe the background state after worker disposal.
                scenario.moveToState(Lifecycle.State.CREATED)
                SystemClock.sleep(4_000)
            }
        } finally {
            Log.i("HyprLuaGate", "tiles=$count initialFrames=$initialFrames attempts=$numAttempts refusals=$refusals " +
                "cpuMs=${Process.getElapsedCpuTime() - cpu} durationMs=${SystemClock.elapsedRealtime() - start} latenciesMs=${latencies.joinToString(",")} readyWaitsMs=${readyWaits.joinToString(",")}")
        }
        assertEquals("Initial Clock frames", count, initialFrames)
        assertEquals("Every click updates state or reports a reason", numAttempts, latencies.size + refusals)
        org.junit.Assert.assertTrue("At least one user event completed", latencies.isNotEmpty())
    }

    private fun selectWorkspace(number: Int) {
        compose.onNodeWithContentDescription("Рабочее пространство $number").performClick()
    }

    private fun toggle(id: WindowId) =
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("tiling-window-${id.value}")))

    private fun refusalVisible(id: WindowId): Boolean =
        compose.onAllNodes(hasText("Попробуйте ещё раз.", substring = true) and
            hasAnyAncestor(hasTestTag("tiling-window-${id.value}")))
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun available(id: WindowId) = runCatching { toggle(id).assertIsEnabled() }.isSuccess
}
