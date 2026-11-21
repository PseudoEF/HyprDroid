package dev.hyprdroid.launcher.modules

import dev.hyprdroid.launcher.workspaceengine.WindowId

/**
 * Ordered collection of built-in tile modules. The registry is the only lookup point for the UI,
 * the picker and the command panel; unknown or legacy ids resolve to `null` and are shown as a
 * neutral picker instead of crashing the tile.
 */
internal class ModuleRegistry(modules: List<TileModule>) {
    val modules: List<TileModule>
    private val byId: Map<String, TileModule>

    init {
        require(modules.all { it.id.isNotBlank() }) { "Module id must not be blank" }
        require(modules.map { it.id }.distinct().size == modules.size) { "Module ids must be unique" }
        this.modules = modules.toList()
        this.byId = modules.associateBy { it.id }
    }

    fun find(moduleId: String?): TileModule? = moduleId?.takeIf { it.isNotBlank() }?.let(byId::get)

    fun createState(moduleId: String?, windowId: WindowId): ModuleState? = find(moduleId)?.createState(windowId)

    companion object {
        /** Persisted marker for a fresh tile whose content has not been chosen yet. */
        const val UNASSIGNED = "unassigned"
    }
}
