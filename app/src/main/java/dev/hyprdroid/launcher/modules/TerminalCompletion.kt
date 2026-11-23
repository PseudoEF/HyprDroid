package dev.hyprdroid.launcher.modules

internal enum class CompletionPackages { NONE, CATALOG, INSTALLED, BOTH }

internal class TerminalCompletion(input: String) {
    private val prefix = input.trimStart()
    private val parts = prefix.trimEnd().split(Regex("\\s+"))
    private val argument = parts.firstOrNull().equals("hypr", true) &&
        (parts.size >= 3 || parts.size == 2 && prefix.lastOrNull()?.isWhitespace() == true)

    val packageSource = if (argument && parts.size <= 3) {
        when (parts[1].lowercase()) {
            "install", "search" -> CompletionPackages.CATALOG
            "info" -> CompletionPackages.BOTH
            "remove" -> CompletionPackages.INSTALLED
            else -> CompletionPackages.NONE
        }
    } else CompletionPackages.NONE

    fun suggestions(catalog: List<String>, installed: List<String>): List<String> {
        if (prefix.isBlank() || '\n' in prefix || '\r' in prefix) return emptyList()
        val candidates = when {
            !argument -> commands
            parts.size > 3 -> emptyList()
            parts[1].equals("repo", true) -> listOf("hypr repo list", "hypr repo update")
            parts[1].equals("list", true) -> listOf("hypr list --installed")
            else -> {
                val ids = when (packageSource) {
                    CompletionPackages.CATALOG -> catalog
                    CompletionPackages.INSTALLED -> installed
                    CompletionPackages.BOTH -> catalog + installed
                    CompletionPackages.NONE -> emptyList()
                }
                ids.distinct().sorted().map { "hypr ${parts[1].lowercase()} $it" }
            }
        }
        val normalized = parts.joinToString(" ") +
            if (prefix.lastOrNull()?.isWhitespace() == true) " " else ""
        return candidates.filter { it.startsWith(normalized, ignoreCase = true) && !it.equals(normalized, true) }
            .take(8)
    }

    private companion object {
        val commands = listOf(
            "help", "clear", "history", "history clear", "modules", "date", "droidfetch",
            "demo install", "lua help", "lua run droidfetch", "hypr help", "hypr search",
            "hypr list", "hypr repo list", "hypr repo update", "hypr info", "hypr install", "hypr remove", "hypr update",
        )
    }
}
