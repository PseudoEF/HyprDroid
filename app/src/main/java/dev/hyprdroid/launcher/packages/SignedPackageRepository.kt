package dev.hyprdroid.launcher.packages

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONObject

internal data class PackageRepositoryTrust(
    val keyId: String,
    val publicKey: String,
    val minimumRevision: Int,
) {
    init {
        require(minimumRevision > 0)
        require(keyId.matches(Regex("[a-z0-9-]{1,64}")) && keyId != AndroidPackages.SAMPLE_KEY_ID)
    }
}

/** Network catalogue and artifact transport only. Installation stays in HyprPackageManager. */
internal class SignedPackageRepository(
    private val trust: PackageRepositoryTrust?,
    private val cache: File,
    private val download: PackageDownload,
    private val reservedIds: Set<String>,
) : PackageCatalog, PackageArtifactSource {
    override val description = "PseudoEF/HyprDroid-Packages"
    private var cached: Snapshot? = null

    @Synchronized
    fun status(): String {
        if (trust == null) return "$description · ключ издателя не утверждён"
        val revision = load()?.revision ?: return "$description · каталог ещё не загружен"
        return "$description · ревизия $revision"
    }

    @Synchronized
    override fun packages(): List<CatalogPackage> =
        load()?.records.orEmpty().values.filter { it.active }.map { it.entry }

    @Synchronized
    fun refresh(): Int {
        requireTrust()
        val previous = load()
        val bytes = download.read(CATALOG_URL, ENVELOPE_LIMIT)
        val next = decode(bytes)
        if (previous != null) {
            if (next.revision < previous.revision ||
                next.revision == previous.revision && !next.payload.contentEquals(previous.payload)
            ) throw PackageException("Обнаружен откат или подмена ревизии каталога")
            if (next.revision == previous.revision) return next.revision
            for ((id, old) in previous.records) {
                val replacement = next.records[id] ?: throw PackageException("Каталог потерял запись $id")
                if (replacement.entry.manifest.version < old.entry.manifest.version ||
                    replacement.entry.manifest.version == old.entry.manifest.version &&
                    (replacement.entry != old.entry || !old.active && replacement.active)
                ) throw PackageException("Обнаружен откат или подмена версии $id")
            }
        }
        checkDirectoryCreated(checkNotNull(cache.parentFile))
        val staged = Files.createTempFile(cache.parentFile!!.toPath(), "catalog-", ".tmp")
        try {
            staged.toFile().outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(staged, cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            deletePackageStaging(staged.toFile())
        }
        cached = next
        return next.revision
    }

    @Synchronized
    override fun open(entry: CatalogPackage) = (load()?.records?.get(entry.manifest.id)).let { record ->
        if (record?.active != true || record.entry != entry) {
            throw PackageException("Пакет больше не разрешён текущим каталогом")
        }
        download.read(checkNotNull(entry.downloadUrl), ARCHIVE_LIMIT).inputStream()
    }

    @Synchronized
    fun signedEvidence(entry: CatalogPackage): ByteArray {
        val snapshot = load() ?: throw PackageException("Нет проверенного сетевого каталога")
        val record = snapshot.records[entry.manifest.id]
        if (record?.active != true || record.entry != entry) {
            throw PackageException("Пакет не разрешён текущим каталогом")
        }
        return snapshot.envelope.copyOf()
    }

    @Synchronized
    fun verifyEvidence(manifest: PackageManifest, bytes: ByteArray): CatalogPackage {
        val proof = decode(bytes)
        val record = proof.records[manifest.id]
        val latest = load()?.records?.get(manifest.id)
        if (record?.active != true || record.entry.manifest != manifest ||
            latest?.active != true || latest.entry != record.entry
        ) throw PackageException("Установленная версия не подтверждена действующим каталогом")
        return record.entry
    }

    private fun load(): Snapshot? {
        if (trust == null || !cache.exists()) return null
        return cached ?: decode(cache.inputStream().use { readPackageBytes(it, ENVELOPE_LIMIT) })
            .also { cached = it }
    }

    private fun requireTrust() = trust ?: throw PackageException("Публичный ключ GitHub-репозитория не утверждён")

    private fun decode(bytes: ByteArray): Snapshot = packageMetadata("Повреждён подписанный каталог") {
        val pinned = requireTrust()
        if (bytes.size > ENVELOPE_LIMIT) throw PackageException("Каталог слишком большой")
        val envelope = parsePackageJson(bytes)
        envelope.requireFields("keyId", "payload", "signature")
        if (envelope.requiredString("keyId") != pinned.keyId) throw PackageException("Неизвестный ключ каталога")
        val payload = try {
            Base64.getDecoder().decode(envelope.requiredString("payload"))
        } catch (_: IllegalArgumentException) {
            throw PackageException("Некорректный Base64 каталога")
        }
        if (payload.size > PAYLOAD_LIMIT) throw PackageException("Каталог слишком большой")
        val signature = envelope.requiredString("signature").decodeHex(64)
        val key = Ed25519PublicKeyParameters(pinned.publicKey.decodeHex(32), 0)
        val message = DOMAIN.toByteArray() + payload
        if (!key.verify(Ed25519.Algorithm.Ed25519, null, message, 0, message.size, signature, 0)) {
            throw PackageException("Подпись каталога Ed25519 недействительна")
        }
        val json = parsePackageJson(payload)
        json.requireFields("schemaVersion", "repository", "revision", "packages")
        if (json.get("schemaVersion") != 1 || json.requiredString("repository") != REPOSITORY) {
            throw PackageException("Неизвестная схема или идентификатор каталога")
        }
        val revision = json.get("revision") as? Int ?: throw PackageException("Некорректная ревизия каталога")
        if (revision < pinned.minimumRevision) throw PackageException("Ревизия ниже закреплённой в APK")
        val entries = json.getJSONArray("packages")
        if (entries.length() > 64) throw PackageException("Каталог содержит слишком много пакетов")
        val records = linkedMapOf<String, Record>()
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            item.requireFields("manifest", "sha256", "signature", "url", "status")
            val manifest = PackageManifest.decode(item.getJSONObject("manifest"))
            if (manifest.id in records || manifest.id in reservedIds) throw PackageException("Конфликт ID пакета")
            val digest = item.requiredString("sha256").also { it.decodeHex(32) }
            val artifactSignature = item.requiredString("signature").also { it.decodeHex(64) }
            val url = item.requiredString("url")
            val filename = "${manifest.id}-${manifest.version}.hyp"
            if (url != "$RELEASE_ROOT/${manifest.id}-v${manifest.version}/$filename") {
                throw PackageException("Адрес пакета выходит за пределы GitHub Releases")
            }
            val active = when (item.requiredString("status")) {
                "active" -> true
                "revoked" -> false
                else -> throw PackageException("Неизвестный статус пакета")
            }
            records[manifest.id] = Record(CatalogPackage(manifest, digest, artifactSignature, pinned.keyId, url), active)
        }
        Snapshot(revision, payload, records, bytes.copyOf())
    }

    private fun JSONObject.requireFields(vararg fields: String) {
        if (keys().asSequence().toSet() != fields.toSet()) throw PackageException("Некорректные поля каталога")
    }

    private data class Record(val entry: CatalogPackage, val active: Boolean)
    private data class Snapshot(val revision: Int, val payload: ByteArray, val records: Map<String, Record>, val envelope: ByteArray)

    companion object {
        const val REPOSITORY = "PseudoEF/HyprDroid-Packages"
        const val RELEASE_ROOT = "https://github.com/$REPOSITORY/releases/download"
        const val CATALOG_URL = "$RELEASE_ROOT/catalog-v1/catalog.json"
        const val DOMAIN = "HyprDroid catalog v1\n"
        private const val ENVELOPE_LIMIT = 128 * 1024
        private const val PAYLOAD_LIMIT = 64 * 1024
        private const val ARCHIVE_LIMIT = 8 * 1024 * 1024
    }
}
