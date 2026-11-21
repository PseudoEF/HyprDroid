package dev.hyprdroid.launcher.ui

import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WindowMin

/**
 * Compose-facing view of one internal tiling session. The engine instance is an immutable snapshot
 * published by the ViewModel; the surface never mutates it.
 */
internal data class TilingState(
    val open: Boolean = false,
    val workspaceId: Int = 0,
    val focusedWindowId: WindowId? = null,
    val engine: WorkspaceEngine = WorkspaceEngine(),
)

/**
 * Open tiling sessions keyed by workspace id. Each workspace owns an independent snapshot, so a
 * session in one workspace never replaces or closes the session of another.
 */
internal typealias TilingSessions = Map<Int, TilingState>

/** Session of [workspaceId] only when it is open; foreign workspaces never leak into this lookup. */
internal fun TilingSessions.sessionFor(workspaceId: Int): TilingState? = this[workspaceId]?.takeIf { it.open }

/** Publishes [engine] as the open session of [workspaceId] without touching any other workspace. */
internal fun TilingSessions.withOpenSession(workspaceId: Int, engine: WorkspaceEngine): TilingSessions {
    val session = TilingState(
        open = true,
        workspaceId = workspaceId,
        focusedWindowId = engine.focusedWindowId,
        engine = engine.snapshot(),
    )
    return this + (workspaceId to session)
}

/** Refreshes an already open session; closed or foreign workspaces are never opened implicitly. */
internal fun TilingSessions.withPublishedSession(workspaceId: Int, engine: WorkspaceEngine): TilingSessions =
    if (this[workspaceId]?.open == true) withOpenSession(workspaceId, engine) else this

/** Removes only the addressed workspace surface; the persisted engine tree stays available. */
internal fun TilingSessions.withoutSession(workspaceId: Int): TilingSessions = this - workspaceId

/** Engines and open sessions rebuilt from persisted per-workspace keys. */
internal data class RestoredTiling(
    val engines: Map<Int, WorkspaceEngine>,
    val sessions: TilingSessions,
)

/**
 * Rebuilds every workspace after a restart. A workspace receives a session only when both its engine
 * and its persisted open flag are present; damaged or unknown keys are skipped instead of blocking
 * the remaining workspaces.
 */
internal fun restoreTiling(enginesByKey: Map<String, WorkspaceEngine>, openWorkspaces: Set<String>): RestoredTiling {
    val engines = mutableMapOf<Int, WorkspaceEngine>()
    val sessions = mutableMapOf<Int, TilingState>()
    for ((key, engine) in enginesByKey) {
        val workspaceId = key.toIntOrNull() ?: continue
        engines[workspaceId] = engine
        if (key in openWorkspaces) sessions[workspaceId] = TilingState(
            open = true,
            workspaceId = workspaceId,
            focusedWindowId = engine.focusedWindowId,
            engine = engine.snapshot(),
        )
    }
    return RestoredTiling(engines, sessions)
}

/** Normalized divider ratio derived from the first-child size and the split area; clamped to the engine range. */
internal fun tilingRatio(availablePx: Int, firstSizePx: Int): Float {
    val available = availablePx.coerceAtLeast(0)
    if (available == 0) return WorkspaceEngine.MIN_RATIO
    val ratio = firstSizePx.coerceIn(0, available).toFloat() / available
    return ratio.coerceIn(WorkspaceEngine.MIN_RATIO, WorkspaceEngine.MAX_RATIO)
}

/** Minimum pixel sizes for one internal window derived from its dp manifest at the given density scale. */
internal fun tilingWindowMin(window: WorkspaceWindow, densityScale: Float): WindowMin = WindowMin(
    widthPx = (window.minWidthDp * densityScale).toInt(),
    heightPx = (window.minHeightDp * densityScale).toInt(),
)
