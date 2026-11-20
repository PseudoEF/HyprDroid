package dev.hyprdroid.launcher.workspaceengine

/** One node of the placement binary tree. Leaves refer to windows, splits hold two children. */
internal sealed interface LayoutNode

/** Binary split node: two children with a normalized ratio. No lists, exactly two slots. */
internal data class SplitNode(
    val id: SplitId,
    var orientation: Orientation,
    var ratio: Float,
    var first: LayoutNode,
    var second: LayoutNode,
) : LayoutNode

/** Leaf of the placement tree bound to one internal window. */
internal data class LeafNode(val windowId: WindowId) : LayoutNode
