package dev.hyprdroid.launcher.workspaceengine

/** Stable identifier of one split inside a workspace tree. */
internal data class SplitId(val value: String) {
    override fun toString(): String = "SplitId($value)"
}
