package dev.hyprdroid.launcher.workspaceengine

internal enum class Orientation {
    HORIZONTAL, VERTICAL;

    fun opposite(): Orientation = if (this == HORIZONTAL) VERTICAL else HORIZONTAL;
}
