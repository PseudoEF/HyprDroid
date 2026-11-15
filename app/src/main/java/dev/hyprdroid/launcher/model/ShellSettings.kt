package dev.hyprdroid.launcher.model

const val MAX_DOCK_APPS = 4

/** Additive shell preferences; the existing workspace schema and file are unchanged. */
data class ShellSettings(
    val favorites: Set<String> = emptySet(),
    val wallpaperUri: String? = null,
)
