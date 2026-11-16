package dev.hyprdroid.launcher.model

enum class PanelSide { LEFT, RIGHT }
enum class PanelScope(val title: String, val home: Boolean, val apps: Boolean) {
    HOME("Только Home", true, false), APPS("Поверх приложений", false, true), EVERYWHERE("Везде", true, true),
}
enum class MarkerVisibility(val title: String, val alpha: Int) {
    NORMAL("Обычный", 255), TRANSLUCENT("Полупрозрачный", 160), HIDDEN("Скрытый", 0),
}
/** Known, locally rendered modules. Unknown IDs remain data and are never executed. */
enum class PanelModule(val title: String) {
    QUICK_LAUNCH("Quick Launch"), SYSTEM_STATUS("System Status"), CLOCK("Часы"), DATE("Дата"), BATTERY("Батарея");
    fun supports(side: PanelSide) = this != QUICK_LAUNCH || side == PanelSide.RIGHT
}
data class PanelSettings(
    val scope: PanelScope = PanelScope.HOME,
    val markerWidthDp: Int = 3, val touchWidthDp: Int = 24,
    val heightDp: Int = 160, val positionPercent: Int = 50, val insetDp: Int = 0,
    val visibility: MarkerVisibility = MarkerVisibility.TRANSLUCENT,
    val modules: List<String> = listOf("SYSTEM_STATUS", "QUICK_LAUNCH"),
    val widgets: List<PanelWidget> = emptyList(),
) {
    fun validated(): PanelSettings {
        require(modules.size <= 64 && modules.distinct().size == modules.size && modules.all { it.isNotBlank() && it.length <= 100 })
        require(widgets.size <= 32 && widgets.map { it.appWidgetId }.distinct().size == widgets.size)
        require(widgets.all { it.moduleId in modules })
        return copy(markerWidthDp = markerWidthDp.coerceIn(1, 12), touchWidthDp = touchWidthDp.coerceIn(12, 64),
            heightDp = heightDp.coerceIn(80, 200), positionPercent = positionPercent.coerceIn(10, 90), insetDp = insetDp.coerceIn(0, 64), widgets = widgets.map { it.validated() })
    }
}
data class PanelsSettings(val left: PanelSettings = PanelSettings(modules = listOf("CLOCK", "DATE", "BATTERY")),
    val right: PanelSettings = PanelSettings()) {
    operator fun get(side: PanelSide) = if (side == PanelSide.LEFT) left else right
    fun with(side: PanelSide, value: PanelSettings) = if (side == PanelSide.LEFT) copy(left = value) else copy(right = value)
}
