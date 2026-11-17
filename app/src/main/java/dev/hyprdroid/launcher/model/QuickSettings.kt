package dev.hyprdroid.launcher.model

enum class QuickTile(val title: String, val glyph: String) {
    WIFI("Wi-Fi", "◉"), BLUETOOTH("Bluetooth", "ᛒ"), TORCH("Фонарик", "☀"), DND("Не беспокоить", "◒"),
    BRIGHTNESS("Яркость", "☼"), VOLUME("Громкость", "♫"), ROTATION("Автоповорот", "↻"), SETTINGS("Настройки", "⚙");
    val moduleId: String get() = "QS_$name"
    companion object { fun fromModule(id: String) = entries.find { it.moduleId == id } }
}
/** Same module IDs as side panels; absent configuration enables the topbar collection only. */
data class QuickSettingsConfig(val topbar: List<String> = QuickTile.entries.map { it.moduleId }) {
    fun validated(): QuickSettingsConfig {
        require(topbar.size <= 64 && topbar.distinct().size == topbar.size && topbar.all { it.length in 1..100 })
        return this
    }
}
data class QuickTileState(val detail: String = "Проверка…", val active: Boolean? = null, val value: Float? = null, val adjustable: Boolean = false)
