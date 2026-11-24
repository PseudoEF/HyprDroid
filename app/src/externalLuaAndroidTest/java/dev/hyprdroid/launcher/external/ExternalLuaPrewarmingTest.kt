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
class ExternalLuaPrewarmingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun compareClockWorkspaces() {
        check(BuildConfig.V2_FIXTURE_LUA)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val count = requireNotNull(args.getString("clockTiles")).toInt()
        val warm = requireNotNull(args.getString("prewarm")).toBooleanStrict()
        require(count in setOf(0, 1, 2, 4, 8))
        ExperimentalExternalLua.prewarmExperiment = warm
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
        var misses = 0
        var initialFrames = 0
        var numAttempts = 0
        val start = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(5_000)
                initialFrames = ids.count { available(it) }
                for (round in 0..5) {
                    if (round == 2) {
                        selectWorkspace(2)
                        scenario.moveToState(Lifecycle.State.CREATED)
                        SystemClock.sleep(3_000)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        selectWorkspace(1)
                        SystemClock.sleep(2_000)
                    }
                    if (ids.isNotEmpty()) {
                        val id = ids[round % ids.size]
                        numAttempts++
                        val clickAt = SystemClock.elapsedRealtime()
                        try {
                            compose.waitUntil(4_000) { available(id) }
                            val node = toggle(id)
                            val previous = requireNotNull(node.fetchSemanticsNode().config.getOrNull(
                                androidx.compose.ui.semantics.SemanticsProperties.ToggleableState))
                            node.performClick()
                            compose.waitUntil(3_000) {
                                available(id) && toggle(id).fetchSemanticsNode().config.getOrNull(
                                    androidx.compose.ui.semantics.SemanticsProperties.ToggleableState) != previous
                            }
                            latencies += SystemClock.elapsedRealtime() - clickAt
                        } catch (_: AssertionError) {
                            misses++
                        } catch (_: ComposeTimeoutException) {
                            misses++
                        }
                    }
                    SystemClock.sleep(2_000)
                }
                // Capture steady idle after disposal and the reserve TTL.
                scenario.moveToState(Lifecycle.State.CREATED)
                SystemClock.sleep(4_000)
            }
        } finally {
            ExperimentalExternalLua.prewarmExperiment = false
            Log.i("HyprLuaBenchmark", "tiles=$count warm=$warm initialFrames=$initialFrames attempts=$numAttempts misses=$misses " +
                "cpuMs=${Process.getElapsedCpuTime() - cpu} durationMs=${SystemClock.elapsedRealtime() - start} latenciesMs=${latencies.joinToString(",")}")
        }
        assertEquals("Initial Clock frames", count, initialFrames)
        assertEquals("User interactions without visible change", 0, misses)
    }

    private fun selectWorkspace(number: Int) {
        compose.onNodeWithContentDescription("Рабочее пространство $number").performClick()
    }

    private fun toggle(id: WindowId) =
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("tiling-window-${id.value}")))

    private fun available(id: WindowId) = runCatching { toggle(id).assertIsEnabled() }.isSuccess
}
