package dev.hyprdroid.launcher.modules

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.hyprdroid.launcher.workspaceengine.WindowId

/** Visual marker of a built-in module; the renderer maps it to a lightweight glyph. */
internal enum class ModuleGlyph { CLOCK, SYSTEM, APPS, COMMAND }

/**
 * State of one module instance. A state object belongs to exactly one [WindowId] and is never
 * shared between tiles, so several copies of the same module stay independent.
 */
internal abstract class ModuleState(val windowId: WindowId) {
    /** Releases receivers, callbacks and other resources held by this instance. */
    open fun dispose() = Unit
}

/**
 * Static description of one internal HyprDroid module together with its Compose entry point.
 * Built-in modules are registered in code; there is no archive, script or dynamic code loading.
 */
internal interface TileModule {
    val id: String
    val title: String
    val summary: String
    val glyph: ModuleGlyph

    fun createState(windowId: WindowId): ModuleState

    @Composable
    fun Content(state: ModuleState, modifier: Modifier)
}
