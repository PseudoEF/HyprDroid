package dev.hyprdroid.launcher.packages

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519

internal class HypPackageVerifier(pinnedKeys: Map<String, String>) {
    private val keys = pinnedKeys.mapValues { (_, key) -> Ed25519PublicKeyParameters(key.decodeHex(32), 0) }

    fun prepare(entry: CatalogPackage, artifact: InputStream, stage: File): PackageManifest {
        val key = keys[entry.keyId] ?: throw PackageException("Издатель пакета отсутствует в списке доверенных")
        val expectedDigest = entry.sha256.decodeHex(32)
        val signature = entry.signature.decodeHex(64)
        // Read the exact archive into a bounded buffer once. The same bytes are authenticated and unpacked.
        val bytes = readPackageBytes(artifact, MAX_ARCHIVE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        if (!MessageDigest.isEqual(digest, expectedDigest)) throw PackageException("SHA-256 пакета не совпадает")
        if (!key.verify(Ed25519.Algorithm.Ed25519, null, bytes, 0, bytes.size, signature, 0)) {
            throw PackageException("Подпись Ed25519 пакета недействительна")
        }

        val archive = stage.resolve("archive.hyp")
        archive.writeBytes(bytes)
        val content = stage.resolve("content")
        checkDirectoryCreated(content)
        ZipFile(archive).use { unpack(it, content) }
        val manifestFile = content.resolve("manifest.json")
        if (!manifestFile.isFile) throw PackageException("В архиве отсутствует manifest.json")
        val manifest = packageMetadata("Повреждён манифест пакета") {
            val manifestBytes = manifestFile.inputStream().use { readPackageBytes(it, MAX_MANIFEST_BYTES) }
            PackageManifest.decode(parsePackageJson(manifestBytes))
        }
        if (manifest != entry.manifest) throw PackageException("Манифест отличается от записи каталога")
        if (!content.resolve(manifest.entry).isFile) throw PackageException("Отсутствует точка входа")
        if (!archive.delete()) throw PackageException("Не удалось завершить подготовку пакета")
        return manifest
    }

    private fun unpack(archive: ZipFile, content: File) {
        if (archive.size() !in 1..MAX_ENTRIES) throw PackageException("Некорректное число записей в архиве")
        val paths = mutableSetOf<String>()
        var totalBytes = 0L
        for (member in archive.entries()) {
            val path = member.name.removeSuffix("/")
            validateArchivePath(path)
            if (!paths.add(path.lowercase(Locale.ROOT))) throw PackageException("Повторяющийся путь в архиве")

            val size = member.size
            val maximum = if (path == "manifest.json") MAX_MANIFEST_BYTES else MAX_FILE_BYTES
            if (size !in 0L..maximum.toLong() || totalBytes + size > MAX_EXPANDED_BYTES ||
                member.compressedSize !in 0L..MAX_ARCHIVE_BYTES.toLong() || size > member.compressedSize * 100 + 64 * 1024
            ) {
                throw PackageException("Превышены лимиты архива")
            }
            val destination = content.resolve(path)
            if (member.isDirectory) {
                if (size != 0L) throw PackageException("Некорректная запись каталога в архиве")
                checkDirectoryCreated(destination)
                continue
            }

            checkDirectoryCreated(checkNotNull(destination.parentFile))
            if (!destination.createNewFile()) throw PackageException("Конфликт путей в архиве")
            var written = 0L
            val crc = CRC32()
            archive.getInputStream(member).use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > size || totalBytes + written > MAX_EXPANDED_BYTES) {
                            throw PackageException("Превышен заявленный размер файла")
                        }
                        crc.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (written != size || crc.value != member.crc) throw PackageException("Нарушена целостность ZIP-записи")
            totalBytes += written
        }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 24 * 1024 * 1024
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 16 * 1024
        private const val MAX_ENTRIES = 256
    }
}

internal fun checkDirectoryCreated(directory: File) {
    if (!directory.isDirectory && !directory.mkdirs()) throw PackageException("Не удалось создать каталог пакета")
}

private fun String.decodeHex(size: Int): ByteArray {
    if (length != size * 2 || any { it !in '0'..'9' && it !in 'a'..'f' }) {
        throw PackageException("Некорректная контрольная сумма, подпись или публичный ключ")
    }
    return ByteArray(size) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
