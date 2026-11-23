package dev.hyprdroid.launcher.modules

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.terminalHistoryStore by preferencesDataStore("terminal_history")

internal interface TerminalHistory {
    suspend fun entries(): List<String>
    suspend fun record(command: String)
    suspend fun clear()
}

internal class StoredTerminalHistory(private val store: DataStore<Preferences>) : TerminalHistory {
    constructor(context: Context) : this(context.applicationContext.terminalHistoryStore)
    private val key = stringPreferencesKey("commands_v1")

    val commands: Flow<List<String>> = store.data.map { decodeHistory(it[key]) }
    val navigationCommands = commands.catch {
        if (it is IOException) emit(emptyList()) else throw it
    }

    override suspend fun entries() = commands.first()

    override suspend fun record(command: String) {
        store.edit { prefs ->
            prefs[key] = JSONArray(appendHistory(decodeHistory(prefs[key]), command)).toString()
        }
    }

    override suspend fun clear() {
        store.edit { it.remove(key) }
    }
}

internal const val HISTORY_LIMIT = 100
internal const val COMMAND_LENGTH_LIMIT = 4096
internal const val BATCH_LIMIT = 40

internal fun parseTerminalInput(text: String): List<String> =
    text.lineSequence().map(String::trim).filter(String::isNotEmpty).take(BATCH_LIMIT + 1).toList()

internal fun appendHistory(history: List<String>, command: String): List<String> =
    if (command.isBlank() || command == history.lastOrNull()) history
    else (history + command).takeLast(HISTORY_LIMIT)

internal fun decodeHistory(json: String?): List<String> {
    if (json == null) return emptyList()
    val array = try {
        JSONArray(json)
    } catch (error: org.json.JSONException) {
        throw IOException("Invalid terminal history", error)
    }
    return (maxOf(0, array.length() - HISTORY_LIMIT) until array.length()).mapNotNull {
        (array.opt(it) as? String)?.takeIf { value -> value.length <= COMMAND_LENGTH_LIMIT }
    }
}

internal class HistoryNavigation {
    private var entries = emptyList<String>()
    private var index = 0
    private var draft = ""
    private var displayed: String? = null

    fun previous(current: String, history: List<String>): String {
        if (displayed != current) {
            entries = history
            index = entries.size
            draft = current
        }
        if (index > 0) index--
        return (entries.getOrNull(index) ?: draft).also { displayed = it }
    }

    fun next(current: String): String {
        if (displayed != current) return current
        if (index < entries.size) index++
        return (entries.getOrNull(index) ?: draft).also { displayed = it }
    }

    fun reset() {
        displayed = null
        entries = emptyList()
    }
}
