package dev.hyprdroid.launcher.model

const val MAX_DOCK_APPS = 4

enum class DesktopMode { CLEAN, CLASSIC }
enum class DockVisibility { ALWAYS, AUTO, HIDDEN }
enum class SidebarModule { QUICK_LAUNCH, SYSTEM_STATUS }

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
)
