package dev.hyprdroid.launcher.model

const val WORKSPACE_COUNT = 3

/** Stable workspace IDs and component IDs, independent of the installed-app snapshot. */
data class Workspace(val id: Int, val pinnedApps: Set<String> = emptySet())

data class WorkspaceSettings(
    val workspaces: List<Workspace> = List(WORKSPACE_COUNT) { Workspace(it) },
    val selectedWorkspace: Int = 0,
    val gridLayout: Boolean = true,
) {
    fun withPinned(workspaceId: Int, componentId: String, pinned: Boolean): WorkspaceSettings {
        require(workspaceId in 0 until WORKSPACE_COUNT)
        require(componentId.substringBefore('/').isNotBlank() && componentId.substringAfter('/', "").isNotBlank())
        return copy(workspaces = workspaces.map { workspace ->
            if (workspace.id != workspaceId) workspace else workspace.copy(
                pinnedApps = if (pinned) workspace.pinnedApps + componentId else workspace.pinnedApps - componentId,
            )
        })
    }

    fun withoutPackages(packages: Set<String>): WorkspaceSettings = copy(
        workspaces = workspaces.map { workspace ->
            workspace.copy(pinnedApps = workspace.pinnedApps.filterNot { it.substringBefore('/') in packages }.toSet())
        },
    )
}
