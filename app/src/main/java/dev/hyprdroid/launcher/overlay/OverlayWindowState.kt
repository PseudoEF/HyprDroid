package dev.hyprdroid.launcher.overlay

import dev.hyprdroid.launcher.model.PanelSide
import dev.hyprdroid.launcher.model.PanelsSettings

/** What the overlay windows actually show; any changed field requires a rebuild. */
internal data class OverlayWindowState(
    val side: PanelSide?,
    val panels: PanelsSettings,
    val width: Int,
    val height: Int,
    val density: Float,
)

/** Handles are reusable only for exactly the sides that currently request `APPS` scope. */
internal fun handlesCover(panels: PanelsSettings, shown: Set<PanelSide>, attached: (PanelSide) -> Boolean): Boolean {
    val expected = PanelSide.entries.filter { panels[it].scope.apps }.toSet()
    return shown == expected && expected.all(attached)
}
