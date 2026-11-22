package dev.hyprdroid.launcher.packages

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class PackageException(message: String, cause: Throwable? = null) : IOException(message, cause)

@ConsistentCopyVisibility
internal data class PackageVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<PackageVersion> {
    override fun compareTo(other: PackageVersion): Int =
        compareValuesBy(this, other, PackageVersion::major, PackageVersion::minor, PackageVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val format = Regex("(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})")

        fun parse(text: String): PackageVersion {
            val parts = format.matchEntire(text)?.groupValues?.drop(1)?.map { it.toIntOrNull() }
            val (major, minor, patch) = parts ?: throw PackageException("Некорректная версия пакета")
            if (major == null || minor == null || patch == null) {
                throw PackageException("Некорректная версия пакета")
            }
            return PackageVersion(major, minor, patch)
        }
    }
}

internal data class PackageManifest(
    val id: String,
    val version: PackageVersion,
    val apiVersion: Int,
    val runtime: String,
    val entry: String,
    val permissions: Set<String>,
    val dependencies: Map<String, PackageVersion>,
) {
    fun encode() = JSONObject()
        .put("id", id)
        .put("version", version.toString())
        .put("apiVersion", apiVersion)
        .put("runtime", runtime)
        .put("entry", entry)
        .put("permissions", JSONArray(permissions.sorted()))
        .put("dependencies", JSONObject(dependencies.mapValues { it.value.toString() }))

    companion object {
        private val fields = setOf("id", "version", "apiVersion", "runtime", "entry", "permissions", "dependencies")
        private val permissionsV1 = setOf("terminal.print", "system.device", "system.battery", "ui.window")

        fun decode(json: JSONObject): PackageManifest {
            if (json.keys().asSequence().toSet() != fields) throw PackageException("Некорректные поля манифеста")
            val id = validatePackageId(json.requiredString("id"))
            val version = PackageVersion.parse(json.requiredString("version"))
            val api = json.get("apiVersion") as? Int
            if (api != 1) throw PackageException("Неподдерживаемая версия Plugin API")
            val runtime = json.requiredString("runtime")
            if (runtime != "lua") throw PackageException("Неподдерживаемый runtime")
            val entry = json.requiredString("entry")
            validateArchivePath(entry)
            if (!entry.endsWith(".lua")) throw PackageException("Точка входа должна быть файлом Lua")

            val permissions = json.getJSONArray("permissions")
            if (permissions.length() > permissionsV1.size) throw PackageException("Слишком много разрешений")
            val grants = (0 until permissions.length()).map { permissions.get(it) as? String }.toSet()
            if (grants.size != permissions.length() || grants.any { it !in permissionsV1 }) {
                throw PackageException("Неизвестные или повторяющиеся разрешения")
            }

            val dependencies = json.getJSONObject("dependencies")
            if (dependencies.length() > 16) throw PackageException("Слишком много зависимостей")
            val requirements = dependencies.keys().asSequence().associateWith { dependency ->
                validatePackageId(dependency)
                if (dependency == id) throw PackageException("Пакет зависит от самого себя")
                PackageVersion.parse(dependencies.requiredString(dependency))
            }
            return PackageManifest(id, version, api, runtime, entry, grants.filterNotNull().toSet(), requirements)
        }
    }
}

private val packageIdFormat = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
private val archiveSegmentFormat = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,79}")

internal fun validatePackageId(id: String): String {
    if (id.length > 80 || !packageIdFormat.matches(id)) throw PackageException("Некорректный ID пакета")
    return id
}

internal fun validateArchivePath(path: String) {
    val segments = path.split('/')
    if (path.length > 240 || segments.size > 16 || segments.any { !archiveSegmentFormat.matches(it) }) {
        throw PackageException("Недопустимый путь в архиве")
    }
}

internal fun JSONObject.requiredString(key: String): String =
    get(key) as? String ?: throw PackageException("Ожидается строка: $key")

internal fun parsePackageJson(bytes: ByteArray): JSONObject {
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    // Android and the JVM ship different org.json parsers. Tokenize strictly on both platforms.
    return JsonReader(text.reader()).use { reader ->
        reader.strictness = Strictness.STRICT
        val json = reader.readPackageValue() as? JSONObject
        if (json == null || reader.peek() != JsonToken.END_DOCUMENT) throw PackageException("Ожидается объект JSON")
        json
    }
}

private fun JsonReader.readPackageValue(depth: Int = 0): Any {
    if (depth > 8) throw PackageException("Метаданные пакета слишком глубоко вложены")
    return when (peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val json = JSONObject()
            beginObject()
            while (hasNext()) {
                val name = nextName()
                if (json.has(name)) throw PackageException("Повторяющееся поле метаданных: $name")
                json.put(name, readPackageValue(depth + 1))
            }
            endObject()
            json
        }
        JsonToken.BEGIN_ARRAY -> {
            val json = JSONArray()
            beginArray()
            while (hasNext()) json.put(readPackageValue(depth + 1))
            endArray()
            json
        }
        JsonToken.STRING -> nextString()
        JsonToken.NUMBER -> nextString().toIntOrNull() ?: throw PackageException("Ожидается целое число")
        else -> throw PackageException("Недопустимый тип поля метаданных")
    }
}

internal inline fun <T> packageMetadata(error: String, read: () -> T): T =
    try {
        read()
    } catch (failure: PackageException) {
        throw failure
    } catch (failure: IOException) {
        throw PackageException(error, failure)
    } catch (failure: JSONException) {
        throw PackageException(error, failure)
    }
