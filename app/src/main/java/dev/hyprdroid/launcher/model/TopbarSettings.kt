package dev.hyprdroid.launcher.model

enum class TopbarMode(val title: String) { HOME_ONLY("Home only"), ALWAYS("Always visible"), AUTO_HIDE("Auto-hide") }
enum class AppBarRule(val title: String) { AUTO("Auto"), SHOW("Always show"), HIDE("Always hide") }
/** Inert compatibility record for the retired global TopBar experiment. Not a runtime policy. */
data class TopbarSettings(val mode: TopbarMode = TopbarMode.HOME_ONLY, val widthPercent: Int = 96,
    val heightDp: Int = 48, val opacityPercent: Int = 95, val contextAware: Boolean = true,
    val manuallyHidden: Boolean = false, val appRules: Map<String, AppBarRule> = emptyMap()) {
    fun validated(): TopbarSettings {
        require(widthPercent in 50..100 && heightDp in 48..80 && opacityPercent in 50..100)
        require(appRules.size <= 256 && appRules.keys.all { it.length in 1..255 && it.matches(Regex("[A-Za-z0-9_.]+")) })
        return this
    }
}
