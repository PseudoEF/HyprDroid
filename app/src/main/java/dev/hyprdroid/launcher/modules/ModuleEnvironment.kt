package dev.hyprdroid.launcher.modules

import androidx.compose.runtime.staticCompositionLocalOf
import dev.hyprdroid.launcher.data.LauncherApp

/** Host services available to a tile module without letting it own the catalogue or navigation. */
internal class ModuleEnvironment(
    val apps: List<LauncherApp> = emptyList(),
    val loadingApps: Boolean = false,
    val catalogError: Boolean = false,
    val launchApp: (String) -> Unit = {},
    val retryApps: () -> Unit = {},
)

internal val LocalModuleEnvironment = staticCompositionLocalOf { ModuleEnvironment() }

internal val LocalTileModuleRegistry = staticCompositionLocalOf<ModuleRegistry?> { null }
