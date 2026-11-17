package dev.hyprdroid.launcher.model

enum class TopbarMode(val title: String) { HOME_ONLY("Home only"), ALWAYS("Always visible"), AUTO_HIDE("Auto-hide") }
enum class AppBarRule(val title: String) { AUTO("Auto"), SHOW("Always show"), HIDE("Always hide") }
enum class ForegroundCategory { UNKNOWN, NORMAL, GAME, VIDEO }
enum class BarVisibility { NONE, BAR, REVEAL }
data class TopbarSettings(val mode: TopbarMode = TopbarMode.HOME_ONLY, val widthPercent: Int = 96,
    val heightDp: Int = 48, val opacityPercent: Int = 95, val contextAware: Boolean = true,
    val manuallyHidden: Boolean = false, val appRules: Map<String, AppBarRule> = emptyMap()) {
    fun validated(): TopbarSettings {
        require(widthPercent in 50..100 && heightDp in 48..80 && opacityPercent in 50..100)
        require(appRules.size <= 256 && appRules.keys.all { it.length in 1..255 && it.matches(Regex("[A-Za-z0-9_.]+")) })
        return this
    }
}
/** Conservative hints, never a claim that another Activity is fullscreen or playing video. */
fun TopbarSettings.visibility(home: Boolean, safe: Boolean, ime: Boolean, app: String?, category: ForegroundCategory,
    idle: Boolean, temporaryReveal: Boolean): BarVisibility {
    if (!safe || home || mode == TopbarMode.HOME_ONLY || ime) return BarVisibility.NONE
    val rule = appRules[app] ?: AppBarRule.AUTO
    if (!temporaryReveal) {
        if (app == null && appRules.values.any { it == AppBarRule.HIDE }) return BarVisibility.NONE
        if (manuallyHidden || rule == AppBarRule.HIDE) return BarVisibility.NONE
        if (contextAware && rule == AppBarRule.AUTO && category in setOf(ForegroundCategory.GAME, ForegroundCategory.VIDEO)) return BarVisibility.NONE
        if (mode == TopbarMode.AUTO_HIDE && idle && rule != AppBarRule.SHOW) return BarVisibility.REVEAL
    }
    return BarVisibility.BAR
}
