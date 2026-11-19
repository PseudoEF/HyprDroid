package dev.hyprdroid.launcher.workspaceengine

/** Contract of a future internal module (.hmod). No archive, WebView, JS or execution in this version. */
internal data class ModuleManifest(
    val id: String,
    val name: String,
    val version: String,
    val entrypoint: String,
    val permissions: List<String> = emptyList(),
) {
    fun isValid(): Boolean = id.isNotBlank() && name.isNotBlank() && version.isNotBlank() && entrypoint.isNotBlank() && permissions.all { it.isNotBlank() }
}
