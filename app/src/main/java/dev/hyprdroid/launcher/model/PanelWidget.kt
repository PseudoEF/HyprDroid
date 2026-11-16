package dev.hyprdroid.launcher.model

/** A standard Android widget instance, placed in the same ordered module list as built-ins. */
data class PanelWidget(val appWidgetId: Int, val provider: String, val title: String,
    val widthPercent: Int = 100, val heightDp: Int = 180) {
    val moduleId get() = "ANDROID_WIDGET:$appWidgetId"
    fun validated(): PanelWidget {
        require(appWidgetId > 0 && validComponent(provider) && title.length <= 200)
        return copy(widthPercent = widthPercent.coerceIn(25, 100), heightDp = heightDp.coerceIn(64, 600))
    }
}
enum class WidgetBindingStage { ALLOCATED, BIND, CONFIGURE, COMMIT }
data class PendingPanelWidget(val side: PanelSide, val widget: PanelWidget, val stage: WidgetBindingStage)
