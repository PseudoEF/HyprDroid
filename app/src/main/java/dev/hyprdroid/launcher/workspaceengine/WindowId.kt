package dev.hyprdroid.launcher.workspaceengine

/** Stable identifier of one internal window inside a workspace. */
internal data class WindowId(val value: String) {
    override fun toString(): String = "WindowId($value)"
}
