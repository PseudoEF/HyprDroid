package dev.hyprdroid.launcher.workspaceengine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** In-memory placement engine for internal workspace modules. Pure Kotlin, no Android dependencies. */
internal class WorkspaceEngine(private val initial: WorkspaceTree = WorkspaceTree()) {
    var root: LayoutNode? = initial.root
    private val windows: MutableMap<WindowId, WorkspaceWindow> = initial.windows.toMutableMap()
    var focusedWindowId: WindowId? = initial.focusedWindowId

    companion object {
        const val ENGINE_SCHEMA_VERSION = 1
        const val MIN_RATIO = 0.05f
        const val MAX_RATIO = 0.95f
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
        if (focusedWindowId == windowId) focusedWindowId = null
        return true
    }

    fun focusWindow(windowId: WindowId): Boolean {
        if (!windows.containsKey(windowId)) return false
        focusedWindowId = windowId
        return true
    }

    fun resizeSplit(splitId: SplitId, ratio: Float): Boolean {
        val split = findSplit(root, splitId) ?: return false
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) return false
        split.ratio = ratio
        return true
    }

    /** Pure layout pass: rectangles are produced inside the available area, mins may clip. */
    fun calculateLayout(area: LayoutArea, mins: Map<WindowId, WindowMin>): List<PlacedWindow> {
        val result = mutableListOf<PlacedWindow>()
        fun walk(node: LayoutNode?, rect: LayoutRect) {
            if (node == null) return
            if (node is LeafNode) {
                val min = mins[node.windowId] ?: WindowMin(0, 0)
                result += PlacedWindow(
                    windowId = node.windowId,
                    rect = rect,
                    minWidthPx = min.widthPx,
                    minHeightPx = min.heightPx,
                    constrained = rect.width < min.widthPx || rect.height < min.heightPx,
                )
            }
            else {
                val split = node as SplitNode
                val firstRect = if (split.orientation == Orientation.HORIZONTAL) LayoutRect(x = 0, y = 0, width = (rect.width * split.ratio).toInt(), height = rect.height)
                else LayoutRect(x = 0, y = 0, width = rect.width, height = (rect.height * split.ratio).toInt())
                val secondRect = if (split.orientation == Orientation.HORIZONTAL) LayoutRect(x = firstRect.x + firstRect.width, y = firstRect.y, width = rect.width - firstRect.width, height = rect.height)
                else LayoutRect(x = firstRect.x, y = firstRect.y, width = rect.width, height = rect.height - firstRect.height)
                walk(split.first, firstRect)
                walk(split.second, secondRect)
            }
        }
        walk(root, LayoutRect(x = 0, y = 0, width = area.widthPx, height = area.heightPx))
        return result
    }

    fun serialize(): String {
        val node = root
        val rootJson: Any? = if (node == null) null else when (node) {
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
        val document = try { JSONObject(raw) } catch (error: JSONException) { throw IOException("Invalid workspace engine state", error) }
        val version = document.optInt("version", -1)
        if (version != ENGINE_SCHEMA_VERSION) throw IOException("Unsupported workspace engine schema version: $version")
        val engine = WorkspaceEngine()
        val node = document.opt("root")
        engine.root = if (node == null || JSONObject.NULL.equals(node)) null else {
            try { nodeFromJson(node) } catch (error: JSONException) { throw IOException("Invalid workspace engine state", error) }
        }
        val windowsJson = document.optJSONArray("windows") ?: JSONArray()
        val loaded = mutableMapOf<WindowId, WorkspaceWindow>()
        for (index in 0 until windowsJson.length()) {
            val item = windowsJson.optJSONObject(index) ?: throw IOException("Invalid workspace engine state")
            val windowId = WindowId(item.getString("windowId"))
            loaded[windowId] = WorkspaceWindow(windowId = windowId, moduleId = item.getString("moduleId"), minWidthDp = item.optInt("minWidthDp", 0), minHeightDp = item.optInt("minHeightDp", 0))
        }
        engine.windows.clear()
        loaded.forEach { entry -> engine.windows[entry.key] = entry.value }
        engine.focusedWindowId = if (document.isNull("focused")) null else WindowId(document.getString("focused"))
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
            if (value.has("leaf")) LeafNode(WindowId(value.getString("leaf")))
            else {
                val orientation = try { Orientation.valueOf(value.getString("orientation")) } catch (error: IllegalArgumentException) { throw IOException("Invalid workspace engine state: unknown orientation", error) }
                SplitNode(
                    id = SplitId(value.getString("id")),
                    orientation = orientation,
                    ratio = value.optDouble("ratio", 0.5).toFloat(),
                    first = nodeFromJson(value.get("first")) ?: throw IOException("Invalid workspace engine state: split first child is missing"),
                    second = nodeFromJson(value.get("second")) ?: throw IOException("Invalid workspace engine state: split second child is missing"),
                )
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
