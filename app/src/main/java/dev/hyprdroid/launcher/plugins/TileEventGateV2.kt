package dev.hyprdroid.launcher.plugins

/** Owned by one tile. A new frame invalidates callbacks from the previous tree. */
internal class TileEventGateV2 {
    private var frame: TileNode? = null
    private var revision = 0L
    private var lastSequence = 0L
    private var lastAcceptedMs: Long? = null

    fun publish(tree: TileNode?): Long {
        frame = tree
        return ++revision
    }

    fun accept(frameRevision: Long, sequence: Long, event: TileInteraction, nowMs: Long): Boolean {
        if (frameRevision != revision || sequence <= lastSequence || frame?.accepts(event) != true) return false
        if (lastAcceptedMs?.let { nowMs - it < 350 } == true) return false
        lastAcceptedMs = nowMs
        lastSequence = sequence
        return true
    }
}
