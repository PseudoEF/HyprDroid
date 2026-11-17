package dev.hyprdroid.launcher.model

/** Independent of desktop pins: disabling the experiment never deletes assignments. */
data class WorkspaceSessions(val enabled: Boolean = false, val apps: List<List<String>> = List(WORKSPACE_COUNT) { emptyList() }) {
    fun assign(workspace: Int, component: String, selected: Boolean): WorkspaceSessions {
        require(workspace in apps.indices)
        require(component.length in 3..512 && component.contains('/'))
        val next = if (selected) (apps[workspace] + component).distinct() else apps[workspace] - component
        require(next.size <= 2) { "В сессии уже два приложения" }
        return copy(apps = apps.mapIndexed { index, old -> if (index == workspace) next else old })
    }
}
data class SessionEvent(val time: Long, val workspace: Int, val action: String, val result: String)
