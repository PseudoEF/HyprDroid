package dev.hyprdroid.launcher.plugins

internal data class TileClockV2(val date: String, val time24: String, val time12: String)

internal data class TileSystemSnapshotV2(
    val clock: TileClockV2?,
    val batteryLevel: Int?,
    val charging: Boolean?,
)

internal data class TileBatteryV2(val level: Int?, val charging: Boolean?)

/** A bounded snapshot builder. Unauthorized data providers are never called. */
internal fun tileSystemV2(
    permissions: Set<String>,
    readTime: () -> TileClockV2,
    readBattery: () -> TileBatteryV2,
): TileSystemSnapshotV2 {
    val clock = if ("system.time" in permissions) readTime().takeIf {
        listOf(it.date, it.time24, it.time12).all { text -> text.isPluginText(32) }
    } else null
    val battery = if ("system.battery" in permissions) readBattery() else null
    return TileSystemSnapshotV2(clock, battery?.level?.takeIf { it in 0..100 }, battery?.charging)
}
