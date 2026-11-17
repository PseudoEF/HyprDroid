package dev.hyprdroid.launcher.model

const val MAX_DOCK_APPS = 4

enum class DesktopMode { CLEAN, CLASSIC }
enum class DockVisibility { ALWAYS, AUTO, HIDDEN }
enum class SidebarModule { QUICK_LAUNCH, SYSTEM_STATUS }
enum class ShellButtonVisibility { AUTO, SHOWN, HIDDEN;
    fun visible(mode: DesktopMode) = this == SHOWN || this == AUTO && mode == DesktopMode.CLASSIC
}
enum class WallpaperScale { CROP, FIT, STRETCH }

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
    val drawerTopbar: ShellButtonVisibility = ShellButtonVisibility.AUTO,
    val drawerDock: ShellButtonVisibility = ShellButtonVisibility.AUTO,
    val sidebarTopbar: Boolean = true,
    val wallpaperScale: WallpaperScale = WallpaperScale.CROP,
    val wallpaperMotion: Boolean = true,
)

/** Presentation is independent of workspace contents and quick-launch records. */
data class ShellSettings(
    val favorites: Set<String> = emptySet(),
    val wallpaperUri: String? = null,
    val sidebar: List<WorkspaceItem> = emptyList(),
    val presentation: ShellPresentation = ShellPresentation(),
    val overlay: OverlaySettings = OverlaySettings(),
    val panelConfiguration: PanelsSettings? = null,
    val pendingWidget: PendingPanelWidget? = null,
) {
    // Lazy migration preserves the existing overlay switch, geometry and enabled right modules.
    val panels: PanelsSettings get() = panelConfiguration ?: PanelsSettings(right = PanelSettings(
        scope = if (overlay.enabled) PanelScope.EVERYWHERE else PanelScope.HOME,
        heightDp = overlay.heightDp, positionPercent = overlay.positionPercent, insetDp = overlay.insetDp,
        modules = listOf("SYSTEM_STATUS", "QUICK_LAUNCH").filter { id -> presentation.sidebarModules.any { it.name == id } },
    ))
}
