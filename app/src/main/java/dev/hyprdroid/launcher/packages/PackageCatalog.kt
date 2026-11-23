package dev.hyprdroid.launcher.packages

import java.io.InputStream

internal data class CatalogPackage(
    val manifest: PackageManifest,
    val sha256: String,
    val signature: String,
    val keyId: String,
    val downloadUrl: String? = null,
) {
    val filename: String get() = "${manifest.id}-${manifest.version}.hyp"
}

internal interface PackageCatalog {
    val description: String
    fun packages(): List<CatalogPackage>
}

internal fun interface PackageArtifactSource {
    fun open(entry: CatalogPackage): InputStream
}

internal class BundledPackageCatalog(
    private val openCatalog: () -> InputStream,
) : PackageCatalog {
    override val description = "Локальный тестовый каталог из APK"

    private val entries by lazy {
        packageMetadata("Повреждён каталог пакетов") {
            val bytes = openCatalog().use { readPackageBytes(it, 64 * 1024) }
            val json = parsePackageJson(bytes)
            if (json.get("schemaVersion") != 1) throw PackageException("Неподдерживаемая версия каталога")
            val packages = json.getJSONArray("packages")
            if (packages.length() > 64) throw PackageException("Слишком большой каталог")
            val entries = (0 until packages.length()).map { index ->
                val entry = packages.getJSONObject(index)
                CatalogPackage(
                    manifest = PackageManifest.decode(entry.getJSONObject("manifest")),
                    sha256 = entry.requiredString("sha256"),
                    signature = entry.requiredString("signature"),
                    keyId = entry.requiredString("keyId"),
                )
            }
            if (entries.distinctBy { it.manifest.id }.size != entries.size) {
                throw PackageException("Каталог содержит повторяющиеся ID")
            }
            entries.sortedBy { it.manifest.id }
        }
    }

    override fun packages(): List<CatalogPackage> = entries
}

internal fun readPackageBytes(input: InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val size = input.read(buffer)
        if (size < 0) return output.toByteArray()
        if (output.size() + size > limit) throw PackageException("Превышен допустимый размер входных данных")
        output.write(buffer, 0, size)
    }
}
