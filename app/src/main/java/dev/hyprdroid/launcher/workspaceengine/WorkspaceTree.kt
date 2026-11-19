package dev.hyprdroid.launcher.workspaceengine

/** Placement state of one workspace: binary tree, its windows and the focused window. */
internal class WorkspaceTree(
    val root: LayoutNode? = null,
    val windows: Map<WindowId, WorkspaceWindow> = emptyMap(),
    val focusedWindowId: WindowId? = null,
) {
    fun has(windowId: WindowId): Boolean = windows.containsKey(windowId)

    fun leafWindowIds(): List<WindowId> {
        val result = mutableListOf<WindowId>()
        fun walk(node: LayoutNode?) {
            if (node == null) return
            if (node is LeafNode) result += node.windowId
            else {
                val split = node as SplitNode
                walk(split.first)
                walk(split.second)
            }
        }
        walk(root)
        return result
    }
}
