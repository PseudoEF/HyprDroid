package dev.hyprdroid.launcher.model

const val MAX_DOCK_APPS = 4

enum class DesktopMode { CLEAN, CLASSIC }
enum class DockVisibility { ALWAYS, AUTO, HIDDEN }
enum class SidebarModule { QUICK_LAUNCH, SYSTEM_STATUS }

/** Optional overlay geometry; Android may further restrict the gesture exclusion. */
data class OverlaySettings(val enabled: Boolean = false, val positionPercent: Int = 50,
    val heightDp: Int = 160, val insetDp: Int = 0) {
    fun validated() = copy(positionPercent = positionPercent.coerceIn(20, 80),
        heightDp = heightDp.coerceIn(80, 200), insetDp = insetDp.coerceIn(0, 48))
}

data class ShellPresentation(
    val desktopMode: DesktopMode = DesktopMode.CLEAN,
    val dockVisibility: DockVisibility = DockVisibility.ALWAYS,
    val sidebarModules: Set<SidebarModule> = SidebarModule.entries.toSet(),
)

/** Presentation is independent of workspace contents and quick-launch records. */
data class ShellSettings(
    val favorites: Set<String> = emptySet(),
    val wallpaperUri: String? = null,
    val sidebar: List<WorkspaceItem> = emptyList(),
    val presentation: ShellPresentation = ShellPresentation(),
    val overlay: OverlaySettings = OverlaySettings(),
)
