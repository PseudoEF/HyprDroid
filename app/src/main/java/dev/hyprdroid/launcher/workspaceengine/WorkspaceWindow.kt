package dev.hyprdroid.launcher.workspaceengine

/** Module bound to an internal window plus its minimum sizes; minimums are dp at this boundary. */
internal data class WorkspaceWindow(
    val windowId: WindowId,
    val moduleId: String,
    val minWidthDp: Int = 0,
    val minHeightDp: Int = 0,
)
