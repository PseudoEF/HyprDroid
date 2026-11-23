package dev.hyprdroid.launcher.packages

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The application keeps one manager per private registry. Terminal tiles share this instance. */
internal class HyprPackageManager(
    root: File,
    private val catalog: PackageCatalog,
    private val artifactSource: PackageArtifactSource,
    private val verifier: HypPackageVerifier,
    private val registry: PackageRegistry = FilePackageRegistry(root),
    private val repository: SignedPackageRepository? = null,
) {
    private val lock = Any()
    private val objects = root.resolve("objects")
    private val helloGeneration = MutableStateFlow(0L)
    val helloTileGeneration = helloGeneration.asStateFlow()

    val catalogDescription: String get() = if (repository == null) catalog.description
        else "${catalog.description} · ${repository.description} (проверенный кэш)"

    fun repositoryStatus(): List<String> = listOf(catalog.description) +
        listOfNotNull(repository?.status())

    fun refreshRepository(): Int = synchronized(lock) {
        val repo = repository ?: throw PackageException("Сетевой репозиторий не настроен")
        repo.refresh().also { helloGeneration.value++ }
    }

    private fun catalogEntries(): List<CatalogPackage> {
        val bundled = catalog.packages()
        val remote = try {
            repository?.packages().orEmpty()
        } catch (_: java.io.IOException) {
            // A broken network cache must not make the bundled catalogue unusable.
            emptyList()
        }
        return bundled + remote
    }

    fun available(query: String = ""): List<PackageManifest> {
        val needle = query.trim().lowercase(Locale.ROOT)
        return catalogEntries().map { it.manifest }.filter { needle in it.id }
    }

    fun installed(): List<PackageManifest> = synchronized(lock) {
        registry.read().values.map { it.manifest }.sortedBy { it.id }
    }

    fun info(name: String): PackageDetails = synchronized(lock) {
        val installed = registry.read()
        val available = catalogEntries().associateBy { it.manifest.id }
        val id = resolveName(name, available.keys + installed.keys)
        PackageDetails(available[id]?.manifest, installed[id]?.manifest)
    }

    /** Authorizes the built-in tile adapter using the exact signed installation sample. */
    fun requireDroidFetchTileSample(): Unit = synchronized(lock) {
        val installed = registry.read()[DROIDFETCH_SAMPLE_ID]
            ?: throw PackageException("Сначала установите $DROIDFETCH_SAMPLE_ID")
        if (!installed.manifest.permissions.containsAll(setOf("system.device", "system.battery"))) {
            throw PackageException("DroidFetch требует разрешения на сведения об устройстве и батарее")
        }
        val entry = catalog.packages().singleOrNull {
            it.manifest.id == DROIDFETCH_SAMPLE_ID &&
                it.keyId == AndroidPackages.SAMPLE_KEY_ID &&
                it.sha256 == "999caaac63dd7a2489a7aaa8e86541764b89f57dae7a13b9e716ca84cfa15446"
        }
        if (entry == null || installed.manifest != entry.manifest) {
            throw PackageException("Установленная версия DroidFetch не входит в доверенный список")
        }
        val script = objects.resolve(installed.directory).resolve("content").resolve(installed.manifest.entry)
        val bytes = script.inputStream().use { readPackageBytes(it, 16 * 1024) }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val expected = "bc121413681fa2e03657f1d5cbe1feb1d6801bfe310845b84cd2f0955d5a3b70"
        val actual = digest.joinToString("") { "%02x".format(it) }
        if (actual != expected) throw PackageException("Содержимое установленного DroidFetch изменено")
    }

    /** Returns the exact checked bytes. Runtime must not reopen an installed path. */
    fun verifiedHelloTile(): ByteArray = synchronized(lock) {
        val installed = registry.read()[HELLO_TILE_ID] ?: throw PackageException("Установите hello-tile")
        if (!installed.hasSignedEvidence) throw PackageException("Повторите hypr install hello-tile для проверки источника")
        val repo = repository ?: throw PackageException("Сетевой репозиторий не настроен")
        val directory = objects.resolve(installed.directory)
        val proof = readCheckedFile(directory.resolve("catalog-proof.json"), 128 * 1024)
        val entry = repo.verifyEvidence(installed.manifest, proof)
        requirePublishedHello(entry)
        val expected = Files.newInputStream(directory.resolve("archive.hyp").toPath(), LinkOption.NOFOLLOW_LINKS).use {
            verifier.verifiedEntrypoint(entry, it)
        }
        val installedBytes = readCheckedFile(directory.resolve("content").resolve(entry.manifest.entry), 16 * 1024)
        val sha = MessageDigest.getInstance("SHA-256")
        if (installedBytes.isEmpty() || !MessageDigest.isEqual(sha.digest(expected), sha.digest(installedBytes))) {
            throw PackageException("Точка входа установленного пакета изменена")
        }
        installedBytes
    }

    private fun readCheckedFile(file: File, limit: Int): ByteArray =
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { readPackageBytes(it, limit) }

    fun install(name: String): List<PackageManifest> = synchronized(lock) {
        val current = registry.read()
        val entries = catalogEntries().associateBy { it.manifest.id }
        val id = resolveName(name, entries.keys)
        val requested = entries.getValue(id)
        val installed = current[id]
        if (installed != null && installed.manifest.version >= requested.manifest.version) {
            if (id != HELLO_TILE_ID || installed.hasSignedEvidence || installed.manifest != requested.manifest || requested.downloadUrl == null) {
                return@synchronized emptyList()
            }
            // Only this published identity can re-download over a legacy equal-version installation.
            requirePublishedHello(requested)
        }
        apply(current, plan(listOf(requested), current, entries))
    }

    fun update(): List<PackageManifest> = synchronized(lock) {
        val current = registry.read()
        val entries = catalogEntries().associateBy { it.manifest.id }
        val requested = current.values.mapNotNull { installed ->
            entries[installed.manifest.id]?.takeIf { it.manifest.version > installed.manifest.version }
        }
        apply(current, plan(requested, current, entries))
    }

    fun remove(name: String): PackageManifest = synchronized(lock) {
        val current = registry.read()
        val id = resolveName(name, current.keys)
        val removed = current.getValue(id)
        val dependents = current.values.map { it.manifest }.filter { id in it.dependencies }
        if (dependents.isNotEmpty()) {
            throw PackageException("Пакет требуется: ${dependents.joinToString { it.id }}")
        }
        val next = current - id
        registry.publish(next)
        if (id == HELLO_TILE_ID) helloGeneration.value++
        prune(next)
        removed.manifest
    }

    private fun plan(
        requested: List<CatalogPackage>,
        installed: Map<String, InstalledPackage>,
        available: Map<String, CatalogPackage>,
    ): List<CatalogPackage> {
        val pending = linkedMapOf<String, CatalogPackage>()
        val visiting = mutableSetOf<String>()
        fun visit(entry: CatalogPackage) {
            val manifest = entry.manifest
            if (manifest.id in pending) return
            if (!visiting.add(manifest.id)) throw PackageException("Цикл зависимостей: ${manifest.id}")
            for ((id, minimum) in manifest.dependencies) {
                val existing = installed[id]?.manifest?.version
                if (existing != null && existing >= minimum) continue
                val candidate = available[id]?.takeIf { it.manifest.version >= minimum }
                    ?: throw PackageException("${manifest.id} требует $id версии $minimum или выше")
                visit(candidate)
            }
            visiting.remove(manifest.id)
            pending[manifest.id] = entry
        }

        requested.forEach(::visit)
        val planned = installed.mapValues { it.value.manifest } + pending.mapValues { it.value.manifest }
        if (planned.size > MAX_INSTALLED_PACKAGES) throw PackageException("Превышен лимит числа пакетов")
        validateGraph(planned)
        return pending.values.toList()
    }

    /** Also checks cycles spanning previously installed packages or multiple updates. */
    private fun validateGraph(packages: Map<String, PackageManifest>) {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(manifest: PackageManifest) {
            if (manifest.id in visited) return
            if (!visiting.add(manifest.id)) throw PackageException("Цикл зависимостей: ${manifest.id}")
            for ((id, minimum) in manifest.dependencies) {
                val dependency = packages[id]?.takeIf { it.version >= minimum }
                    ?: throw PackageException("${manifest.id} требует $id версии $minimum или выше")
                visit(dependency)
            }
            visiting.remove(manifest.id)
            visited.add(manifest.id)
        }
        packages.values.forEach(::visit)
    }

    private fun apply(
        current: Map<String, InstalledPackage>,
        plan: List<CatalogPackage>,
    ): List<PackageManifest> {
        prune(current)
        if (plan.isEmpty()) return emptyList()
        checkDirectoryCreated(objects)
        val staged = mutableListOf<File>()
        val next = current.toMutableMap()
        var published = false
        try {
            val updates = plan.map { entry ->
                val stage = Files.createTempDirectory(objects.toPath(), "pkg-").toFile()
                staged.add(stage)
                val source = if (entry.downloadUrl == null) artifactSource
                    else repository ?: throw PackageException("Сетевой репозиторий не настроен")
                val proof = if (entry.downloadUrl == null) null else repository?.signedEvidence(entry)
                val manifest = source.open(entry).use { verifier.prepare(entry, it, stage, proof != null) }
                proof?.let { evidence ->
                    stage.resolve("catalog-proof.json").outputStream().use { it.write(evidence); it.fd.sync() }
                }
                next[manifest.id] = InstalledPackage(manifest, stage.name, proof != null)
                manifest
            }
            registry.publish(next)
            published = true
            if (next[HELLO_TILE_ID] != current[HELLO_TILE_ID]) helloGeneration.value++
            prune(next)
            return updates
        } finally {
            if (!published) staged.forEach(::deletePackageStaging)
        }
    }

    /** Unreachable content may remain after interruption or a cleanup error. */
    private fun prune(packages: Map<String, InstalledPackage>) {
        val reachable = packages.values.mapTo(hashSetOf()) { it.directory }
        val directories = try {
            objects.listFiles()
        } catch (_: SecurityException) {
            null
        }
        directories?.filter { it.name.startsWith("pkg-") && it.name !in reachable }
            ?.forEach(::deletePackageStaging)
    }
}

internal data class PackageDetails(val available: PackageManifest?, val installed: PackageManifest?)


internal fun deletePackageStaging(file: File) {
    try {
        file.deleteRecursively()
    } catch (_: SecurityException) {
        // Best effort after rollback or publication.
    }
}

internal const val DROIDFETCH_SAMPLE_ID = "hyprdroid.droidfetch-sample"


internal const val HELLO_TILE_ID = "hello-tile"

private fun requirePublishedHello(entry: CatalogPackage) {
    val manifest = entry.manifest
    if (entry.keyId != "github-v1" ||
        entry.sha256 != "6b3eebbae549dea3cc6c2cf6ebafe327ca62ce4fbdf06feac184536ff25ff438" ||
        manifest.id != HELLO_TILE_ID || manifest.version.toString() != "1.0.0" ||
        manifest.apiVersion != 1 || manifest.runtime != "lua" || manifest.entry != "main.lua" ||
        manifest.permissions != setOf("ui.tile") || manifest.dependencies.isNotEmpty()
    ) throw PackageException("Эта версия Hello Tile не допущена к экспериментальному запуску")
}

private fun resolveName(name: String, ids: Set<String>): String {
    validatePackageId(name)
    if (name in ids) return name
    val matches = ids.filter { it.substringAfterLast('.') == name }
    return when (matches.size) {
        1 -> matches.single()
        0 -> throw PackageException("Пакет $name не найден")
        else -> throw PackageException("Укажите полный ID: ${matches.sorted().joinToString()}")
    }
}
