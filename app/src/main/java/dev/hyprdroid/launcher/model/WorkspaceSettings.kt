package dev.hyprdroid.launcher.model

import java.io.IOException

const val WORKSPACE_COUNT = 3

/** Stable identity and ordered placement are separate from the installed-app catalogue. */
sealed interface WorkspaceItem {
    val id: String
    data class AppShortcut(val componentId: String, val shortcutId: String? = null) : WorkspaceItem {
        override val id = if (shortcutId == null) "app:$componentId" else "shortcut:$componentId:$shortcutId"
    }
    data class WidgetPlaceholder(override val id: String, val title: String) : WorkspaceItem
    /** Round-trip future item types without rendering or executing their payload. */
    data class Unsupported(override val id: String, val record: String) : WorkspaceItem
}

enum class GridDensity(val cellDp: Int, val title: String) {
    RELAXED(112, "Свободно"), BALANCED(92, "Обычно"), COMPACT(76, "Плотно"),
}

data class WorkspaceAppearance(
    val gridLayout: Boolean = true,
    val iconSizeDp: Int = 48,
    val density: GridDensity = GridDensity.COMPACT,
    val spacingDp: Int = 8,
) {
    fun validated() = copy(iconSizeDp = iconSizeDp.coerceIn(36, 64), spacingDp = spacingDp.coerceIn(4, 24))
}

data class Workspace(
    val id: Int,
    val items: List<WorkspaceItem> = emptyList(),
    val appearance: WorkspaceAppearance = WorkspaceAppearance(),
) {
    val pinnedApps: Set<String> get() = items.filterIsInstance<WorkspaceItem.AppShortcut>().map { it.componentId }.toSet()
}

class WorkspaceMoveConflictException : IOException("Target has a different item with the same identity")

data class WorkspaceSettings(
    val workspaces: List<Workspace> = List(WORKSPACE_COUNT) { Workspace(it) },
    val selectedWorkspace: Int = 0,
) {
    val gridLayout: Boolean get() = workspaces[selectedWorkspace].appearance.gridLayout

    fun withPinned(workspaceId: Int, componentId: String, pinned: Boolean): WorkspaceSettings {
        require(workspaceId in 0 until WORKSPACE_COUNT)
        require(validComponent(componentId))
        return updateWorkspace(workspaceId) { workspace ->
            val app = WorkspaceItem.AppShortcut(componentId)
            workspace.copy(items = when {
                !pinned -> workspace.items.filterNot { it.id == app.id }
                workspace.items.any { it.id == app.id } -> workspace.items
                else -> workspace.items + app
            })
        }
    }

    fun reorder(workspaceId: Int, itemId: String, delta: Int) = updateWorkspace(workspaceId) { workspace ->
        val index = workspace.items.indexOfFirst { it.id == itemId }
        if (index < 0) workspace else {
            val target = (index.toLong() + delta).coerceIn(0, workspace.items.lastIndex.toLong()).toInt()
            val result = workspace.items.toMutableList()
            result.add(target, result.removeAt(index))
            workspace.copy(items = result)
        }
    }

    /** Apply one drag to the latest transaction; concurrent inserts/removals are retained. */
    fun placeBefore(workspaceId: Int, itemId: String, beforeId: String?) = updateWorkspace(workspaceId) {
        it.copy(items = placeItemBefore(it.items, itemId, beforeId))
    }

    fun moveItem(from: Int, to: Int, itemId: String): WorkspaceSettings {
        require(from in 0 until WORKSPACE_COUNT && to in 0 until WORKSPACE_COUNT)
        if (from == to) return this
        val item = workspaces[from].items.find { it.id == itemId } ?: return this
        val existing = workspaces[to].items.find { it.id == itemId }
        if (existing != null && existing != item) throw WorkspaceMoveConflictException()
        return copy(workspaces = workspaces.map { workspace -> when (workspace.id) {
            from -> workspace.copy(items = workspace.items.filterNot { it.id == itemId })
            to -> if (existing == null) workspace.copy(items = workspace.items + item) else workspace
            else -> workspace
        } })
    }

    fun removeItem(workspaceId: Int, itemId: String) = updateWorkspace(workspaceId) {
        it.copy(items = it.items.filterNot { item -> item.id == itemId })
    }

    fun withAppearance(workspaceId: Int, appearance: WorkspaceAppearance) = updateWorkspace(workspaceId) {
        it.copy(appearance = appearance.validated())
    }

    fun withoutPackages(packages: Set<String>) = copy(workspaces = workspaces.map { workspace ->
        workspace.copy(items = workspace.items.filterNot { it is WorkspaceItem.AppShortcut && it.componentId.substringBefore('/') in packages })
    })

    private fun updateWorkspace(id: Int, transform: (Workspace) -> Workspace): WorkspaceSettings {
        require(id in 0 until WORKSPACE_COUNT)
        return copy(workspaces = workspaces.map { if (it.id == id) transform(it) else it })
    }
}

fun validComponent(component: String): Boolean = component.count { it == '/' } == 1 &&
    component.substringBefore('/').isNotBlank() && component.substringAfter('/').isNotBlank() &&
    component.none { it.isISOControl() }

fun placeItemBefore(items: List<WorkspaceItem>, id: String, before: String?): List<WorkspaceItem> {
    if (id == before) return items
    val item = items.find { it.id == id } ?: return items
    if (before != null && items.none { it.id == before }) return items
    return items.filterNot { it.id == id }.toMutableList().apply {
        add(if (before == null) size else indexOfFirst { it.id == before }, item)
    }
}
