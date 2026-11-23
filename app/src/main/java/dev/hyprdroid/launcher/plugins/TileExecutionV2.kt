package dev.hyprdroid.launcher.plugins

internal data class TileExecutionV2(
    val permissions: Set<String>,
    val state: Map<String, TileStateValue>,
    val system: TileSystemSnapshotV2,
    val interaction: TileInteraction? = null,
    val signal: TileSignal? = null,
)

internal data class TileFrameV2(
    val tree: TileNode,
    val state: Map<String, TileStateValue>,
    val timers: List<TileTimer>,
)
