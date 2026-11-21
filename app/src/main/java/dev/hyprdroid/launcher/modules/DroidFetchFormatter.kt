package dev.hyprdroid.launcher.modules

internal data class DroidFetchInfo(
    val os: String,
    val device: String,
    val kernel: String = "N/A",
    val cpu: String,
    val memory: String,
    val battery: String,
    val uptime: String,
    val launcher: String = "HyprDroid",
    val display: String = "N/A",
    val version: String = "N/A",
)

internal object DroidFetchFormatter {
    private val logo = listOf(
        "   /\\  /\\   ",
        "  /  \\/  \\  ",
        " |  H  D  | ",
        " |  ____  | ",
        " | |    | | ",
        " | |____| | ",
        "  \\______/  ",
    )

    fun format(
        info: DroidFetchInfo,
        compact: Boolean = false,
    ): String {
        val fields = listOf(
            "OS" to info.os,
            "Device" to info.device,
            "Kernel" to info.kernel,
            "SoC" to info.cpu,
            "RAM" to info.memory,
            "Battery" to info.battery,
            "Uptime" to info.uptime,
            "WM" to info.launcher,
            "Display" to info.display,
            "Version" to info.version,
        )

        if (compact) {
            return buildString {
                appendLine("user@hyprdroid")
                appendLine("--------------")
                fields.forEach { (key, value) ->
                    appendLine("$key: $value")
                }
            }.trimEnd()
        }

        val lines = listOf(
            "user@hyprdroid",
            "--------------",
        ) + fields.map { (key, value) -> "$key: $value" }

        return (0 until maxOf(logo.size, lines.size))
            .joinToString("\n") { index ->
                val left = logo.getOrNull(index)?.padEnd(14) ?: " ".repeat(14)
                val right = lines.getOrNull(index).orEmpty()
                left + right
            }
            .trimEnd()
    }
}
