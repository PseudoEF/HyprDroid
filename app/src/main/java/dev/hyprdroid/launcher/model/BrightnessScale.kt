package dev.hyprdroid.launcher.model

import kotlin.math.*

/** HLG perceptual approximation using only the documented 1..255 setting range.
 * No internal Android APIs: actual SystemUI calibration/display bounds are not public here.
 */
object BrightnessScale {
    private const val A = 0.17883277
    private const val B = 0.28466892
    private const val C = 0.55991073
    fun toSlider(setting: Int): Float {
        val linear = (setting.coerceIn(1, 255) - 1) / 254.0
        return (if (linear <= 1.0 / 12) sqrt(3 * linear) else A * ln(12 * linear - B) + C).toFloat().coerceIn(0f, 1f)
    }
    fun toSetting(slider: Float): Int {
        require(slider.isFinite())
        val gamma = slider.coerceIn(0f, 1f).toDouble()
        val linear = if (gamma <= 0.5) gamma * gamma / 3 else (exp((gamma - C) / A) + B) / 12
        return (1 + linear.coerceIn(0.0, 1.0) * 254).roundToInt().coerceIn(1, 255)
    }
    fun detail(setting: Int?, automatic: Boolean?): String = when {
        automatic == true -> "Авто · уровень задаёт Android"
        setting == null || automatic == null -> "Уровень API недоступен"
        else -> "≈${(toSlider(setting) * 100).roundToInt()}% · оценка HLG"
    }
}

/** Replace known tile slots without deleting unrelated or unknown modules/widget records. */
fun PanelSettings.withQuickModules(ordered: List<String>): PanelSettings {
    require(ordered.distinct().size == ordered.size && ordered.all { QuickTile.fromModule(it) != null })
    val remaining = ordered.toMutableList()
    return copy(modules = modules.mapNotNull { id ->
        if (QuickTile.fromModule(id) == null) id else if (remaining.isEmpty()) null else remaining.removeAt(0)
    } + remaining)
}
