package dev.hyprdroid.launcher.model

const val MAX_DOCK_APPS = 4

/** Shell schema v2 adds an ordered sidebar while retaining v1 dock and wallpaper keys. */
data class ShellSettings(
    val favorites: Set<String> = emptySet(),
    val wallpaperUri: String? = null,
    val sidebar: List<WorkspaceItem> = emptyList(),
)
