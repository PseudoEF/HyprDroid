package dev.hyprdroid.launcher.packages

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

internal data class InstalledPackage(
    val manifest: PackageManifest,
    val directory: String,
    val hasSignedEvidence: Boolean = false,
    val fixtureMigrationApproved: Boolean = false,
)

internal interface PackageRegistry {
    fun read(): Map<String, InstalledPackage>
    fun publish(packages: Map<String, InstalledPackage>)
}

internal class FilePackageRegistry(private val root: File) : PackageRegistry {
    private val index = root.resolve("registry.json")
    private val objectDirectory = Regex("pkg-[0-9]{1,20}")

    override fun read(): Map<String, InstalledPackage> {
        if (!index.exists()) return emptyMap()
        return packageMetadata("Повреждён реестр пакетов") {
            val json = parsePackageJson(index.inputStream().use { readPackageBytes(it, MAX_REGISTRY_BYTES) })
            if (json.get("schemaVersion") != 1) throw PackageException("Неподдерживаемая версия реестра")
            val records = json.getJSONArray("packages")
            if (records.length() > MAX_INSTALLED_PACKAGES) throw PackageException("Превышен лимит числа пакетов")
            buildMap {
                val directories = mutableSetOf<String>()
                for (index in 0 until records.length()) {
                    val record = records.getJSONObject(index)
                    val manifest = PackageManifest.decode(record.getJSONObject("manifest"))
                    val directory = record.requiredString("directory")
                    if (!objectDirectory.matches(directory) || !directories.add(directory) || containsKey(manifest.id)) {
                        throw PackageException("Некорректная запись в реестре пакетов")
                    }
                    val content = root.resolve("objects").resolve(directory).resolve("content")
                    if (!content.resolve(manifest.entry).isFile || !content.resolve("manifest.json").isFile) {
                        throw PackageException("Отсутствуют файлы установленного пакета ${manifest.id}")
                    }
                    val evidence = if (record.has("signedEvidence")) {
                        if (record.get("signedEvidence") != 1) throw PackageException("Неизвестная схема доказательства")
                        true
                    } else false
                    put(manifest.id, InstalledPackage(manifest, directory, evidence, record.optInt("fixtureMigrationApproved", 0) == 1))
                }
            }
        }
    }

    override fun publish(packages: Map<String, InstalledPackage>) {
        checkDirectoryCreated(root)
        val records = packages.toSortedMap().values.map {
            JSONObject().put("manifest", it.manifest.encode()).put("directory", it.directory).apply {
                if (it.hasSignedEvidence) put("signedEvidence", 1)
                if (it.fixtureMigrationApproved) put("fixtureMigrationApproved", 1)
            }
        }
        val bytes = JSONObject()
            .put("schemaVersion", 1)
            .put("packages", JSONArray(records))
            .toString().toByteArray()
        if (bytes.size > MAX_REGISTRY_BYTES) throw PackageException("Превышен лимит размера реестра")
        val stagedIndex = Files.createTempFile(root.toPath(), "registry-", ".tmp").toFile()
        var committed = false
        try {
            stagedIndex.outputStream().use {
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(stagedIndex.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE)
            committed = true
        } finally {
            if (!committed) deletePackageStaging(stagedIndex)
        }
    }

    companion object {
        private const val MAX_REGISTRY_BYTES = 256 * 1024
    }
}

internal const val MAX_INSTALLED_PACKAGES = 64
