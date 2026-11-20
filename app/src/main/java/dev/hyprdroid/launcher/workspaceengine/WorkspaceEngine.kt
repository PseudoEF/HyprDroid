package dev.hyprdroid.launcher.workspaceengine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.sqrt

/** Orientation for one leaf rect: taller than wide is split top/bottom, otherwise left/right. */
internal fun orientationFor(rect: LayoutRect): Orientation =
    if (rect.height > rect.width) Orientation.VERTICAL else Orientation.HORIZONTAL

/** In-memory placement engine for internal workspace modules. Pure Kotlin, no Android dependencies. */
internal class WorkspaceEngine(private val initial: WorkspaceTree = WorkspaceTree()) {
    var root: LayoutNode? = initial.root
    private val windows: MutableMap<WindowId, WorkspaceWindow> = initial.windows.toMutableMap()
    var focusedWindowId: WindowId? = initial.focusedWindowId
    private val knownLeafIds = mutableSetOf<String>()
    private val knownSplitIds = mutableSetOf<String>()

    companion object {
        const val ENGINE_SCHEMA_VERSION = 1
        const val MIN_RATIO = 0.05f
        const val MAX_RATIO = 0.95f
    }

    /** All internal windows with module manifests; the UI layer derives minimum sizes from them. */
    fun windowsById(): Map<WindowId, WorkspaceWindow> = windows

    /** Allocates a globally unique window id using UUID to prevent collisions. */
    fun nextWindowId(): WindowId = WindowId(UUID.randomUUID().toString())

    /** Allocates a globally unique split id using UUID to prevent collisions. */
    fun nextSplitId(): SplitId = SplitId(UUID.randomUUID().toString())

    /** Independent copy for immutable publishing; mutations never leak back into a published snapshot. */
    internal fun snapshot(): WorkspaceEngine {
        val copy = WorkspaceEngine()
        copy.root = root?.let { copyNode(it) }
        copy.focusedWindowId = focusedWindowId
        windows.forEach { entry -> copy.windows[entry.key] = entry.value }
        return copy
    }

    private fun copyNode(node: LayoutNode): LayoutNode = when (node) {
        is LeafNode -> LeafNode(node.windowId)
        is SplitNode -> SplitNode(
            id = SplitId(node.id.value),
            orientation = node.orientation,
            ratio = node.ratio,
            first = copyNode(node.first),
            second = copyNode(node.second),
        )
    }

    /** Creates the first window of a workspace; an engine that already owns a tree rejects this. */
    fun addFirstWindow(window: WorkspaceWindow): Boolean {
        if (root != null) return false
        root = LeafNode(window.windowId)
        windows[window.windowId] = window
        focusedWindowId = window.windowId
        return true
    }

    /** Splits the window that owns [windowId] and inserts [secondWindow] as the second child. */
    fun splitWindow(windowId: WindowId, orientation: Orientation, splitId: SplitId, secondWindow: WorkspaceWindow): Boolean {
        if (findSplit(root, splitId) != null) return false
        if (windows.containsKey(secondWindow.windowId)) return false
        val found = locate(root, null, null, false, windowId) ?: return false
        val split = SplitNode(
            id = splitId,
            orientation = orientation,
            ratio = 0.5f,
            first = LeafNode(windowId),
            second = LeafNode(secondWindow.windowId),
        )
        if (found.parent == null) root = split
        else {
            val parent = found.parent as SplitNode
            if (found.isFirst) parent.first = split else parent.second = split
        }
        windows[secondWindow.windowId] = secondWindow
        return true
    }

    /**
     * Adaptive split: the immediate orientation follows the current rect of the selected leaf
     * (taller than wide -> top/bottom, otherwise left/right), then the tree is rebalanced for the
     * new window count and the portrait/landscape aspect of [area].
     */
    fun addWindowAdaptive(
        windowId: WindowId,
        splitId: SplitId,
        secondWindow: WorkspaceWindow,
        area: LayoutArea,
        mins: Map<WindowId, WindowMin>,
    ): Boolean {
        val orientation = splitOrientation(windowId, area, mins) ?: return false
        if (!splitWindow(windowId, orientation, splitId, secondWindow)) return false
        reflow(area)
        return true
    }

    /** Orientation for the selected leaf: taller than wide is split top/bottom, otherwise left/right. */
    fun splitOrientation(windowId: WindowId, area: LayoutArea, mins: Map<WindowId, WindowMin>): Orientation? =
        calculateLayout(area, mins).firstOrNull { it.windowId == windowId }?.let { orientationFor(it.rect) }

    /**
     * Rebuilds the split topology for the current leaf count and the orientation of [area]. Every
     * existing SplitNode is reused together with its id and ratio, so the tree, user-resized
     * dividers, window order and window state survive the adaptive pass.
     */
    internal fun reflow(area: LayoutArea): Boolean {
        if (area.widthPx <= 0 || area.heightPx <= 0) return false
        val leaves = collectLeaves(root)
        if (leaves.size <= 1) return false
        val splits = collectSplits(root)
        if (splits.size != leaves.size - 1) return false
        val before = serialize()
        val portrait = area.heightPx >= area.widthPx
        val grouped = mutableListOf<List<LeafNode>>()
        var offset = 0
        targetGroups(leaves.size, portrait).forEach { size ->
            grouped += leaves.subList(offset, offset + size)
            offset += size
        }
        val builder = AdaptiveTreeBuilder(splits)
        root = if (portrait) builder.rows(grouped) else builder.columns(grouped)
        return serialize() != before
    }

    /** Whether the next adaptive layout still gives every window at least one pixel in both axes. */
    fun canAddWindow(area: LayoutArea): Boolean {
        if (area.widthPx < 1 || area.heightPx < 1) return false
        val next = leaves().size + 1
        if (next <= 1) return true
        val portrait = area.heightPx >= area.widthPx
        val groups = targetGroups(next, portrait)
        val rows = if (portrait) groups.size else (groups.maxOrNull() ?: 1)
        val columns = if (portrait) (groups.maxOrNull() ?: 1) else groups.size
        return area.widthPx / columns >= 1 && area.heightPx / rows >= 1
    }

    private fun collectLeaves(node: LayoutNode?): List<LeafNode> = when (node) {
        null -> emptyList()
        is LeafNode -> listOf(node)
        is SplitNode -> collectLeaves(node.first) + collectLeaves(node.second)
    }

    private fun collectSplits(node: LayoutNode?): List<SplitNode> = when (node) {
        null -> emptyList()
        is LeafNode -> emptyList()
        is SplitNode -> listOf(node) + collectSplits(node.first) + collectSplits(node.second)
    }

    private fun targetGroups(count: Int, portrait: Boolean): List<Int> = when (count) {
        2 -> listOf(1, 1)
        3 -> listOf(1, 2)
        4 -> listOf(2, 2)
        5 -> listOf(2, 3)
        6 -> if (portrait) listOf(2, 2, 2) else listOf(3, 3)
        else -> {
            val columns = if (portrait) sqrt(count.toDouble()).toInt().coerceAtLeast(1)
                else ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
            val rows = ceil(count.toDouble() / columns).toInt().coerceAtLeast(1)
            val base = count / rows
            val extra = count % rows
            List(rows) { index -> base + if (index < extra) 1 else 0 }
        }
    }

    /** Reuses the existing split nodes in pre-order so ids and ratios are never replaced. */
    private class AdaptiveTreeBuilder(private val splits: List<SplitNode>) {
        private var index = 0

        private fun next(orientation: Orientation): SplitNode = splits[index++].also { it.orientation = orientation }

        fun line(items: List<LeafNode>, orientation: Orientation): LayoutNode {
            if (items.size == 1) return items.single()
            val split = next(orientation)
            split.first = items.first()
            split.second = line(items.drop(1), orientation)
            return split
        }

        fun rows(groups: List<List<LeafNode>>): LayoutNode {
            if (groups.size == 1) return line(groups.single(), Orientation.HORIZONTAL)
            val split = next(Orientation.VERTICAL)
            split.first = line(groups.first(), Orientation.HORIZONTAL)
            split.second = rows(groups.drop(1))
            return split
        }

        fun columns(groups: List<List<LeafNode>>): LayoutNode {
            if (groups.size == 1) return line(groups.single(), Orientation.VERTICAL)
            val split = next(Orientation.HORIZONTAL)
            split.first = line(groups.first(), Orientation.VERTICAL)
            split.second = columns(groups.drop(1))
            return split
        }
    }

    /** Removes a window and collapses the parent split; the focused window is cleared when lost. */
    fun removeWindow(windowId: WindowId): Boolean {
        val found = locate(root, null, null, false, windowId) ?: return false
        if (found.parent == null) {
            root = null
        }
        else {
            val parent = found.parent as SplitNode
            val survivor = if (found.isFirst) parent.second else parent.first
            val grand = found.grandparent
            if (grand == null) root = survivor
            else {
                val grandSplit = grand as SplitNode
                if (grandSplit.first === parent) grandSplit.first = survivor else grandSplit.second = survivor
            }
        }
        windows.remove(windowId)
        if (focusedWindowId == windowId) {
            val remaining = leaves()
            focusedWindowId = remaining.firstOrNull()
        }
        return true
    }

    fun focusWindow(windowId: WindowId): Boolean {
        if (!windows.containsKey(windowId)) return false
        focusedWindowId = windowId
        return true
    }

    fun resizeSplit(splitId: SplitId, ratio: Float): Boolean {
        val split = findSplit(root, splitId) ?: return false
        if (ratio.isNaN() || ratio.isInfinite() || ratio < MIN_RATIO || ratio > MAX_RATIO) return false
        split.ratio = ratio
        return true
    }

    /** Pure layout pass: rectangles are produced inside the available area, mins may clip. */
    fun calculateLayout(area: LayoutArea, mins: Map<WindowId, WindowMin>): List<PlacedWindow> {
        val width = area.widthPx.coerceAtLeast(0)
        val height = area.heightPx.coerceAtLeast(0)
        val result = mutableListOf<PlacedWindow>()
        fun walk(node: LayoutNode?, rect: LayoutRect) {
            if (node == null) return
            if (node is LeafNode) {
                val min = mins[node.windowId] ?: WindowMin(0, 0)
                val minWidthPx = min.widthPx.coerceAtLeast(0)
                val minHeightPx = min.heightPx.coerceAtLeast(0)
                result += PlacedWindow(
                    windowId = node.windowId,
                    rect = rect,
                    minWidthPx = minWidthPx,
                    minHeightPx = minHeightPx,
                    constrained = rect.width < minWidthPx || rect.height < minHeightPx,
                )
            }
            else {
                val split = node as SplitNode
                val (firstRect, secondRect) = splitChildren(split, rect, mins)
                walk(split.first, firstRect)
                walk(split.second, secondRect)
            }
        }
        walk(root, LayoutRect(x = 0, y = 0, width = width, height = height))
        return result
    }

    /** Splits of the tree with the rect boundaries that the layout pass used; the UI layer renders dividers from them. */
    internal fun splitsWithRects(root: LayoutNode?, area: LayoutRect, mins: Map<WindowId, WindowMin>): List<SplitPlacement> {
        val placements = mutableListOf<SplitPlacement>()
        fun walk(node: LayoutNode?, rect: LayoutRect) {
            if (node == null) return
            if (node is SplitNode) {
                val split = node
                val (firstRect, secondRect) = splitChildren(split, rect, mins)
                placements += SplitPlacement(split, firstRect, secondRect)
                walk(split.first, firstRect)
                walk(split.second, secondRect)
            }
        }
        walk(root, area)
        return placements
    }

    private fun splitChildren(split: SplitNode, rect: LayoutRect, mins: Map<WindowId, WindowMin>): Pair<LayoutRect, LayoutRect> {
        val constrainedAxis = if (split.orientation == Orientation.HORIZONTAL) 0 else 1
        val firstMin = minExtent(split.first, constrainedAxis, mins)
        val secondMin = minExtent(split.second, constrainedAxis, mins)
        val available = if (split.orientation == Orientation.HORIZONTAL) rect.width else rect.height
        val minVisible = if (available >= 2) 1 else 0
        val firstSize: Int
        val secondSize: Int
        if (firstMin + secondMin <= available.toLong()) {
            firstSize = maxOf((available * split.ratio).toInt(), firstMin.toInt())
                .coerceAtMost(available - secondMin.toInt())
                .coerceIn(minVisible, available - minVisible)
        }
        else {
            // Minimums cannot all fit: share the area by ratio so every window stays visible instead of starving one child.
            firstSize = (available * split.ratio).toInt().coerceIn(minVisible, available - minVisible)
        }
        secondSize = available - firstSize
        val firstRect = if (split.orientation == Orientation.HORIZONTAL) LayoutRect(x = rect.x, y = rect.y, width = firstSize, height = rect.height) else LayoutRect(x = rect.x, y = rect.y, width = rect.width, height = firstSize)
        val secondRect = if (split.orientation == Orientation.HORIZONTAL) LayoutRect(x = rect.x + firstSize, y = rect.y, width = secondSize, height = rect.height) else LayoutRect(x = rect.x, y = rect.y + firstSize, width = rect.width, height = secondSize)
        return firstRect to secondRect
    }

    internal data class SplitPlacement(val split: SplitNode, val firstRect: LayoutRect, val secondRect: LayoutRect)

    /**
     * Whether one more window can be added next to the window owning [windowId] without producing an
     * invisible region. Minimum sizes stay soft constraints: when they cannot be satisfied the layout
     * shares the area by ratio, so only the absence of room for two visible regions rejects the split.
     */
    fun canSplit(windowId: WindowId, orientation: Orientation, area: LayoutArea): Boolean {
        locate(root, null, null, false, windowId) ?: return false
        val available = if (orientation == Orientation.HORIZONTAL) area.widthPx else area.heightPx
        return available >= 2
    }

    private fun minExtent(node: LayoutNode?, axis: Int, mins: Map<WindowId, WindowMin>): Long {
        if (node is LeafNode) {
            val min = mins[node.windowId] ?: WindowMin(0, 0)
            return if (axis == 0) min.widthPx.coerceAtLeast(0).toLong() else min.heightPx.coerceAtLeast(0).toLong()
        }
        val split = node as SplitNode
        val firstMin = minExtent(split.first, axis, mins)
        val secondMin = minExtent(split.second, axis, mins)
        return if (split.orientation == Orientation.HORIZONTAL) saturatingAdd(firstMin, secondMin) else maxOf(firstMin, secondMin)
    }

    private fun saturatingAdd(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

    fun serialize(): String {
        val node = root
        val rootJson: Any? = if (node == null) JSONObject.NULL else when (node) {
            is LeafNode -> JSONObject().put("leaf", node.windowId.value)
            is SplitNode -> JSONObject()
                .put("id", node.id.value)
                .put("orientation", node.orientation.name)
                .put("ratio", node.ratio.toDouble())
                .put("first", nodeToJson(node.first))
                .put("second", nodeToJson(node.second))
        }
        val windowsJson = JSONArray()
        windows.values.forEach { window ->
            windowsJson.put(JSONObject().put("windowId", window.windowId.value).put("moduleId", window.moduleId).put("minWidthDp", window.minWidthDp).put("minHeightDp", window.minHeightDp))
        }
        return JSONObject()
            .put("version", ENGINE_SCHEMA_VERSION)
            .put("root", rootJson)
            .put("windows", windowsJson)
            .put("focused", focusedWindowId?.value ?: JSONObject.NULL)
            .toString()
    }

    fun deserialize(raw: String): WorkspaceEngine {
        knownLeafIds.clear()
        knownSplitIds.clear()
        val document = try { JSONObject(raw) } catch (error: JSONException) { throw IOException("Invalid workspace engine state", error) }
        val version = document.optInt("version", -1)
        if (version != ENGINE_SCHEMA_VERSION) throw IOException("Unsupported workspace engine schema version: $version")
        if (!document.has("root")) throw IOException("Invalid workspace engine state: missing root")
        if (!document.has("windows")) throw IOException("Invalid workspace engine state: missing windows")
        if (!document.has("focused")) throw IOException("Invalid workspace engine state: missing focused")
        val engine = WorkspaceEngine()
        val node = document.opt("root")
        engine.root = if (node == null || JSONObject.NULL.equals(node)) null else {
            try { nodeFromJson(node) } catch (error: JSONException) { throw IOException("Invalid workspace engine state", error) }
        }
        val windowsJson = document.optJSONArray("windows") ?: throw IOException("Invalid workspace engine state: windows must be an array")
        val loaded = mutableMapOf<WindowId, WorkspaceWindow>()
        val seenWindowIds = mutableSetOf<String>()
        for (index in 0 until windowsJson.length()) {
            val item = windowsJson.optJSONObject(index) ?: throw IOException("Invalid workspace engine state: window entry must be an object")
            val windowId = WindowId(item.getString("windowId"))
            if (!seenWindowIds.add(windowId.value)) throw IOException("Damaged workspace engine state: duplicate window id ${windowId.value}")
            loaded[windowId] = WorkspaceWindow(windowId = windowId, moduleId = item.getString("moduleId"), minWidthDp = item.optInt("minWidthDp", 0), minHeightDp = item.optInt("minHeightDp", 0))
        }
        engine.windows.clear()
        loaded.forEach { entry -> engine.windows[entry.key] = entry.value }
        engine.focusedWindowId = if (document.isNull("focused")) null else WindowId(document.getString("focused"))
        if (engine.focusedWindowId != null && !loaded.containsKey(engine.focusedWindowId)) throw IOException("Damaged workspace engine state: focused window is missing")
        if (engine.leaves().toSet() != engine.windows.keys.toSet()) throw IOException("Damaged workspace engine state: leaves do not match windows")
        return engine
    }

    private fun nodeToJson(node: LayoutNode?): Any? = if (node == null) JSONObject.NULL else when (node) {
        is LeafNode -> JSONObject().put("leaf", node.windowId.value)
        is SplitNode -> JSONObject()
            .put("id", node.id.value)
            .put("orientation", node.orientation.name)
            .put("ratio", node.ratio.toDouble())
            .put("first", nodeToJson(node.first))
            .put("second", nodeToJson(node.second))
    }

    private fun nodeFromJson(value: Any?): LayoutNode? = when (value) {
        null -> null
        is JSONObject -> {
            if (value.has("leaf")) {
                val leafId = value.getString("leaf")
                if (!knownLeafIds.add(leafId)) throw IOException("Damaged workspace engine state: duplicate window id $leafId")
                LeafNode(WindowId(leafId))
            }
            else {
                if (!value.has("id")) throw IOException("Invalid workspace engine state: split id is missing")
                val splitId = value.getString("id")
                if (!knownSplitIds.add(splitId)) throw IOException("Damaged workspace engine state: duplicate split id $splitId")
                if (!value.has("orientation")) throw IOException("Invalid workspace engine state: split orientation is missing")
                val orientation = try { Orientation.valueOf(value.getString("orientation")) } catch (error: IllegalArgumentException) { throw IOException("Invalid workspace engine state: unknown orientation", error) }
                if (!value.has("ratio")) throw IOException("Invalid workspace engine state: split ratio is missing")
                val ratio = value.optDouble("ratio", 0.5).toFloat()
                if (ratio.isNaN() || ratio.isInfinite() || ratio < MIN_RATIO || ratio > MAX_RATIO) throw IOException("Invalid workspace engine state: split ratio out of range: $ratio")
                val first = nodeFromJson(value.get("first")) ?: throw IOException("Invalid workspace engine state: split first child is missing")
                val second = nodeFromJson(value.get("second")) ?: throw IOException("Invalid workspace engine state: split second child is missing")
                SplitNode(id = SplitId(splitId), orientation = orientation, ratio = ratio, first = first, second = second)
            }
        }
        else -> throw IOException("Invalid workspace engine state: unsupported layout node")
    }

    private fun findSplit(node: LayoutNode?, splitId: SplitId): SplitNode? {
        if (node is SplitNode) {
            if (node.id == splitId) return node
            return findSplit(node.first, splitId) ?: findSplit(node.second, splitId)
        }
        return null
    }

    private fun locate(node: LayoutNode?, parent: LayoutNode?, grandparent: LayoutNode?, isFirst: Boolean, target: WindowId): Location? {
        if (node == null) return null
        if (node is LeafNode) return if (node.windowId == target) Location(node, parent, grandparent, isFirst) else null
        val split = node as SplitNode
        val fromFirst = locate(split.first, split, parent, true, target)
        if (fromFirst != null) return fromFirst
        return locate(split.second, split, parent, false, target)
    }

    private data class Location(val node: LayoutNode, val parent: LayoutNode?, val grandparent: LayoutNode?, val isFirst: Boolean)

    fun leaves(): List<WindowId> {
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
