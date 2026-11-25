package dev.hyprdroid.launcher.modules

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import dev.hyprdroid.launcher.external.TileInputTrace
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.data.TileStateSlot
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.external.ExperimentalExternalLua
import dev.hyprdroid.launcher.external.ExternalLuaBusy
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.plugins.*
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalExternalTileVisible = staticCompositionLocalOf { true }

internal class ExternalV2TileModule(override val id: String, override val title: String) : TileModule {
    override val summary = "Тестовый Lua v2"
    override val glyph = ModuleGlyph.COMMAND
    override fun createState(windowId: WindowId): ModuleState = object : ModuleState(windowId) {}

    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) {
        val app = LocalContext.current.applicationContext
        val manager = remember(app) { AndroidPackages.manager(app) }
        val generation by manager.helloTileGeneration.collectAsState()
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
        val active = LocalExternalTileVisible.current && lifecycle.isAtLeast(Lifecycle.State.STARTED)
        key(state.windowId, generation, active) {
            if (active) ActiveTile(app, state.windowId, generation, modifier)
        }
    }

    @Composable
    private fun ActiveTile(app: Context, window: WindowId, generation: Long, modifier: Modifier) {
        val manager = remember(app) { AndroidPackages.manager(app) }
        val store = remember(app) { WorkspaceEngineStore(app) }
        val slot = remember(window) { TileStateSlot(id, 2, window, if (BuildConfig.V2_OFFICIAL_LUA) "github-v1" else "") }
        val scope = rememberCoroutineScope()
        val gate = remember { TileEventGateV2() }
        var revision by remember { mutableLongStateOf(0) }
        var sequence by remember { mutableLongStateOf(0) }
        var frame by remember { mutableStateOf<TileFrameV2?>(null) }
        var failed by remember { mutableStateOf(false) }
        var inputProblem by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val schedule = remember { TileScheduleV2() }
        DisposableEffect(Unit) {
            Log.d("HyprLuaTile", "v2 tile activated")
            onDispose { Log.d("HyprLuaTile", "v2 tile deactivated") }
        }

        suspend fun run(interaction: TileInteraction? = null, signal: TileSignal? = null, trace: String? = null): Boolean {
            if (busy) {
                TileInputTrace.stage(trace, "refused-busy")
                if (interaction != null) inputProblem = "Тайл обновляется. Попробуйте ещё раз."
                return false
            }
            if (interaction != null) inputProblem = null
            busy = true
            val operationKind = when {
                interaction != null -> "interaction"
                signal is TileSignal.Timer -> "timer"
                signal is TileSignal.Resume -> "resume"
                else -> "retry"
            }
            try {
                val operation = currentCoroutineContext()
                val stillAllowed = { operation.isActive && manager.helloTileGeneration.value == generation }
                val (result, previous, persist) = manager.externalAdmission.run(generation) {
                    val (manifest, source) = withContext(Dispatchers.IO) { manager.verifiedV2Tile(id) }
                    val permissions = manifest.permissions
                    val previous = if ("state.local" in permissions) store.loadTileStateV2(slot, diagnose = signal == TileSignal.Resume) else emptyMap()
                    val system = withContext(Dispatchers.IO) { captureSystem(app, permissions) }
                    val updated = ExperimentalExternalLua.runV2(app, source, TileExecutionV2(
                        permissions, previous, system, interaction, signal,
                    ),
                        dispatch = { manager.externalAdmission.dispatch(generation, it) },
                        authorized = stillAllowed,
                        inputTrace = trace,
                    ) ?: error("Lua v2 did not return a valid frame")
                    Triple(updated, previous, "state.local" in permissions)
                } ?: return false
                val commitStarted = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
                return manager.externalAdmission.commit(generation) {
                    if (persist) store.saveTileStateV2(slot, previous, result.state, stillAllowed)
                    if (!stillAllowed()) return@commit
                    TileInputTrace.stage(trace, "state-committed")
                    val committedAt = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
                    schedule.configure(result.timers, SystemClock.elapsedRealtime())
                    revision = gate.publish(result.tree)
                    frame = result
                    failed = false
                    TileInputTrace.stage(trace, "published")
                    if (BuildConfig.V2_FIXTURE_LUA) {
                        Log.i("HyprLuaGate", "v2 kind=$operationKind commitMs=${committedAt - commitStarted} publishMs=${SystemClock.elapsedRealtime() - committedAt}")
                    }
                    if (operationKind != "timer") Log.d("HyprLuaTile", "v2 presented: $operationKind")
                }
            } catch (cancelled: CancellationException) {
                Log.d("HyprLuaTile", "v2 cancelled: $operationKind")
                throw cancelled
            } catch (_: ExternalLuaBusy) {
                // A saturated queue can drop a timer tick. Keep the last validated
                // frame and its schedule so the next tick can recover.
                manager.externalAdmission.commit(generation) {
                    if (interaction != null) inputProblem = "Очередь Lua занята. Попробуйте ещё раз."
                    TileInputTrace.stage(trace, "refused-admission")
                }
                return false
            } catch (_: Exception) {
                Log.w("HyprLuaTile", "v2 rejected: $operationKind")
                manager.externalAdmission.commit(generation) {
                    if (interaction != null) {
                        inputProblem = "Lua не подтвердил действие. Проверьте состояние тайла."
                        TileInputTrace.stage(trace, "refused-runtime")
                        return@commit
                    }
                    revision = gate.publish(null)
                    frame = null
                    failed = true
                }
                return false
            } finally {
                if (signal is TileSignal.Timer) schedule.completed(signal, SystemClock.elapsedRealtime())
                busy = false
            }
        }

        LaunchedEffect(Unit) {
            schedule.setActive(true, SystemClock.elapsedRealtime())
            for (pause in listOf(0L, 300L, 750L, 1_500L)) {
                delay(pause)
                if (run(signal = TileSignal.Resume) || failed) return@LaunchedEffect
            }
            manager.externalAdmission.commit(generation) { if (frame == null) failed = true }
        }
        LaunchedEffect(frame?.timers) {
            if (frame?.timers.isNullOrEmpty()) return@LaunchedEffect
            while (manager.helloTileGeneration.value == generation) {
                delay(schedule.delayUntilNext(SystemClock.elapsedRealtime()) ?: break)
                if (manager.helloTileGeneration.value != generation) break
                for (signal in schedule.poll(SystemClock.elapsedRealtime())) run(signal = signal)
            }
        }
        val current = frame
        if (current == null) {
            Column(modifier.padding(6.dp)) {
                Text(if (failed) "Lua v2 недоступен" else "Загрузка…", color = Mocha.Text)
                if (failed) TextButton(onClick = { scope.launch { if (!run()) failed = true } }) { Text("Повторить") }
            }
        } else {
            val displayedRevision = revision
            val observeHit = if (BuildConfig.V2_FIXTURE_LUA) Modifier.pointerInput(window) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    TileInputTrace.stage(TileInputTrace.current, "tile-hit tile=${window.value}")
                }
            } else Modifier
            Column(modifier.clipToBounds().then(observeHit).padding(6.dp)) {
                inputProblem?.let { Text(it, color = Mocha.Text, maxLines = 2) }
                // Keep the callback available when a background tick wins the race with a tap.
                // The guard below supplies feedback without accepting a second operation.
                val body = Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                RenderTileTree(current.tree, body, enabled = true) { event ->
                    val trace = if (BuildConfig.V2_FIXTURE_LUA) TileInputTrace.current else null
                    TileInputTrace.stage(trace, "handler tile=${window.value}")
                    when {
                        busy -> {
                            inputProblem = "Тайл обновляется. Попробуйте ещё раз."
                            TileInputTrace.stage(trace, "refused-busy")
                        }
                        gate.accept(displayedRevision, ++sequence, event, SystemClock.elapsedRealtime()) -> {
                            TileInputTrace.stage(trace, "frame-accepted")
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { run(interaction = event, trace = trace) }
                        }
                        else -> {
                            inputProblem = "Событие устарело или повторено. Попробуйте ещё раз."
                            TileInputTrace.stage(trace, "refused-frame")
                        }
                    }
                }
            }
        }
    }
}

private fun captureSystem(app: Context, permissions: Set<String>) = tileSystemV2(
    permissions,
    readTime = {
        val now = ZonedDateTime.now()
        TileClockV2(now.toLocalDate().toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            now.format(DateTimeFormatter.ofPattern("h:mm:ss a", java.util.Locale.ROOT)))
    },
    readBattery = {
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val current = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val charging = when (sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        TileBatteryV2(if (scale > 0 && current in 0..scale) (current * 100L / scale).toInt() else null, charging)
    },
)
