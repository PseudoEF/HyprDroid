package dev.hyprdroid.launcher.developer

import dev.hyprdroid.launcher.packages.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONObject

internal class DeveloperPackageStore(
    private val root: File,
    private val isOfficialId: (String) -> Boolean,
) {
    internal data class Key(val fingerprint: String, val raw: String, val grant: String)
    internal data class Record(val entry: CatalogPackage, val grant: String)
    internal class Preview internal constructor(
        val record: Record,
        private val archive: ByteArray,
        private val metadata: ByteArray,
    ) {
        fun archiveInput() = archive.inputStream()
        fun writeMetadata(destination: File) = writeSynced(destination, metadata)
    }

    private val keyDirectory = root.resolve("keys")
    private val packageDirectory = root.resolve("packages")

    fun keys(): List<Key> = synchronized(lock) { readKeys() }

    fun trust(pem: ByteArray): Key = synchronized(lock) {
        val key = decodeDeveloperPublicKey(pem)
        val keys = readKeys()
        keys.firstOrNull { it.fingerprint == key.fingerprint }?.let { return@synchronized it }
        checkLimit(keys.size < 32, "Достигнут лимит ключей разработчика")
        checkDirectoryCreated(keyDirectory)
        val granted = key.copy(grant = UUID.randomUUID().toString())
        val data = JSONObject().put("raw", granted.raw).put("grant", granted.grant)
        atomicWrite(keyDirectory.resolve(granted.fingerprint), data.toString().encodeToByteArray())
        granted
    }

    fun revoke(fingerprint: String) = synchronized(lock) {
        fingerprint.decodeHex(32)
        if (!keyDirectory.resolve(fingerprint).delete()) throw PackageException("Ключ не найден или не удалён")
    }

    fun packages(): List<Record> = synchronized(lock) {
        packageDirectory.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name }.map(::readRecord)
    }

    fun info(id: String): Record? = synchronized(lock) {
        validatePackageId(id)
        packageDirectory.resolve(id).takeIf(File::isDirectory)?.let(::readRecord)
    }

    fun trusted(record: Record): Boolean = synchronized(lock) {
        readKeys().any { it.fingerprint == record.entry.keyId && it.grant == record.grant }
    }

    fun preview(archive: ByteArray, metadata: ByteArray): Preview = synchronized(lock) {
        checkLimit(archive.size in 1..ARCHIVE_LIMIT && metadata.size in 1..METADATA_LIMIT,
            "Превышен лимит локального пакета")
        val snapshot = archive.copyOf()
        val proof = metadata.copyOf()
        val entry = decodeRecord(proof, "").entry
        checkLimit(!isOfficialId(entry.manifest.id), "ID зарезервирован официальным пакетом")
        checkLimit(sha256(snapshot) == entry.sha256, "SHA-256 пакета не совпадает")
        val signature = entry.signature.decodeHex(64)
        val publisher = readKeys().firstOrNull { key ->
            Ed25519PublicKeyParameters(key.raw.decodeHex(32), 0)
                .verify(Ed25519.Algorithm.Ed25519, null, snapshot, 0, snapshot.size, signature, 0)
        } ?: throw PackageException("Неверная подпись или издатель отсутствует в списке доверенных")
        val record = Record(entry.copy(keyId = publisher.fingerprint), publisher.grant)
        staged { verify(record, publisher, snapshot.inputStream(), it) }
        Preview(record, snapshot, proof)
    }

    fun install(preview: Preview, checkActive: () -> Unit = {}) = synchronized(lock) {
        checkActive()
        val record = preview.record
        val id = record.entry.manifest.id
        checkLimit(!isOfficialId(id), "ID зарезервирован официальным пакетом")
        val key = readKeys().firstOrNull { it.fingerprint == record.entry.keyId && it.grant == record.grant }
            ?: throw PackageException("Доверие к издателю изменилось. Начните импорт заново.")
        val destination = packageDirectory.resolve(id)
        checkLimit(!destination.exists(), "Пакет уже импортирован. Сначала удалите предыдущую копию.")
        checkLimit(packages().size < 16, "Достигнут лимит локальных пакетов")
        staged { stage ->
            verify(record, key, preview.archiveInput(), stage)
            checkLimit(stage.resolve("content").deleteRecursively(), "Не удалось удалить временные файлы")
            preview.writeMetadata(stage.resolve("signature.json"))
            writeSynced(stage.resolve("publisher"), "${key.fingerprint}\n${key.grant}\n".encodeToByteArray())
            checkActive()
            Files.move(stage.toPath(), destination.toPath(), ATOMIC_MOVE)
        }
        Unit
    }

    fun remove(id: String) = synchronized(lock) {
        validatePackageId(id)
        val directory = packageDirectory.resolve(id)
        checkLimit(directory.isDirectory, "Локальный пакет не найден")
        val hidden = packageDirectory.resolve(".removed-${UUID.randomUUID()}")
        Files.move(directory.toPath(), hidden.toPath(), ATOMIC_MOVE)
        hidden.deleteRecursively()
        Unit
    }

    private fun readRecord(directory: File): Record {
        val publisher = directory.resolve("publisher").inputStream().use { readPackageBytes(it, 128) }
            .decodeToString().lineSequence().toList()
        val fingerprint = publisher[0].also { it.decodeHex(32) }
        val grant = publisher.getOrNull(1)?.takeIf { it.length == 36 }
            ?: throw PackageException("Повреждена локальная запись издателя")
        val metadata = directory.resolve("signature.json").inputStream().use { readPackageBytes(it, METADATA_LIMIT) }
        return decodeRecord(metadata, fingerprint).copy(grant = grant)
    }

    private fun readKeys(): List<Key> = keyDirectory.listFiles().orEmpty().filter { !it.name.startsWith(".") }
        .sortedBy { it.name }.map { file ->
            val json = parsePackageJson(file.inputStream().use { readPackageBytes(it, 512) })
            val raw = json.requiredString("raw")
            checkLimit(sha256(ED25519_SPKI + raw.decodeHex(32)) == file.name, "Повреждён публичный ключ")
            Key(file.name, raw, json.requiredString("grant"))
        }

    private fun verify(record: Record, key: Key, archive: java.io.InputStream, stage: File) {
        val verifier = HypPackageVerifier(mapOf(key.fingerprint to key.raw))
        archive.use { verifier.prepare(record.entry, it, stage, retainArchive = true) }
        val entrypoint = stage.resolve("content").resolve(record.entry.manifest.entry)
        checkLimit(entrypoint.length() in 1..16_384, "Некорректный размер точки входа Lua")
    }

    private fun <T> staged(operation: (File) -> T): T {
        checkDirectoryCreated(packageDirectory)
        // All stages are protected by the process-wide store lock. Any existing one
        // is an interrupted transaction or garbage left after a completed removal.
        packageDirectory.listFiles().orEmpty().filter { it.name.startsWith(".") }.forEach {
            checkLimit(it.deleteRecursively(), "Не удалось очистить прерванный импорт")
        }
        val directory = Files.createTempDirectory(packageDirectory.toPath(), ".stage-").toFile()
        return try { operation(directory) } finally { directory.deleteRecursively() }
    }

    companion object {
        const val ARCHIVE_LIMIT = 8 * 1024 * 1024
        const val METADATA_LIMIT = 64 * 1024
        const val KEY_LIMIT = 512
        private val lock = Any()
    }
}

private val ED25519_SPKI = "302a300506032b6570032100".decodeHex(12)
private val publicPem = Regex("-----BEGIN PUBLIC KEY-----[\\r\\n]+([A-Za-z0-9+/=\\r\\n]+)-----END PUBLIC KEY-----")

internal fun decodeDeveloperPublicKey(pem: ByteArray): DeveloperPackageStore.Key {
    checkLimit(pem.size in 1..DeveloperPackageStore.KEY_LIMIT, "Некорректный размер публичного ключа")
    val encoded = publicPem.matchEntire(pem.decodeToString().trim())?.groupValues?.get(1)
        ?: throw PackageException("Выберите публичный Ed25519 PEM, созданный SDK")
    val der = packageMetadata("Повреждён публичный ключ") {
        try {
            Base64.getDecoder().decode(encoded.replace("\r", "").replace("\n", ""))
        } catch (invalid: IllegalArgumentException) {
            throw PackageException("Повреждён публичный ключ", invalid)
        }
    }
    checkLimit(der.size == 44 && der.copyOfRange(0, 12).contentEquals(ED25519_SPKI),
        "Поддерживается только публичный ключ Ed25519")
    val raw = der.copyOfRange(12, 44)
    checkLimit(Ed25519.validatePublicKeyFull(raw, 0), "Недействительный публичный ключ Ed25519")
    return DeveloperPackageStore.Key(sha256(der), hex(raw), "")
}

private fun decodeRecord(bytes: ByteArray, publisher: String): DeveloperPackageStore.Record =
    packageMetadata("Повреждены метаданные подписи SDK") {
        val json = parsePackageJson(bytes)
        checkLimit(json.keys().asSequence().toSet() == setOf("manifest", "sha256", "signature", "url", "status"),
            "Некорректные поля метаданных SDK")
        checkLimit(json.requiredString("status") == "active", "Пакет не активен")
        // SDK signs the archive only. The detached URL is never used as a source.
        json.requiredString("url")
        val manifest = PackageManifest.decode(json.getJSONObject("manifest"))
        DeveloperPackageStore.Record(CatalogPackage(manifest,
            json.requiredString("sha256").also { it.decodeHex(32) },
            json.requiredString("signature").also { it.decodeHex(64) }, publisher), "")
    }

private fun checkLimit(ok: Boolean, message: String) {
    if (!ok) throw PackageException(message)
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun writeSynced(path: File, bytes: ByteArray) {
    path.outputStream().use { it.write(bytes); it.fd.sync() }
}

private fun atomicWrite(path: File, bytes: ByteArray) {
    val temporary = Files.createTempFile(path.parentFile!!.toPath(), ".key-", "").toFile()
    try {
        writeSynced(temporary, bytes)
        Files.move(temporary.toPath(), path.toPath(), ATOMIC_MOVE)
    } finally {
        temporary.delete()
    }
}
