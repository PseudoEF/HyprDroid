package dev.hyprdroid.launcher.developer

import dev.hyprdroid.launcher.modules.CommandEvent
import dev.hyprdroid.launcher.modules.CommandResult
import dev.hyprdroid.launcher.packages.PackageException
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeveloperPackageStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val signingKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
    private val pem = ("-----BEGIN PUBLIC KEY-----\n" +
        Base64.getEncoder().encodeToString(byteArrayOf(48, 42, 48, 5, 6, 3, 43, 101, 112, 3, 33, 0) +
            signingKey.generatePublicKey().encoded) + "\n-----END PUBLIC KEY-----").encodeToByteArray()

    private fun store() = DeveloperPackageStore(folder.root) { it == "hello-tile" }

    @Test fun persistsVerifiedPackageRejectsExecutionAndDoesNotRestoreRevokedGrant() = runBlocking {
        val raw = fixture()
        val keys = store().trust(pem)
        val preview = store().preview(raw.first, raw.second)
        store().install(preview)
        val reopened = store()
        val record = checkNotNull(reopened.info("my-tile"))
        assertEquals(keys.fingerprint, record.entry.keyId)
        assertTrue(reopened.trusted(record))
        val commands = DeveloperCommands(reopened, { error("Opened SAF") }, { _, _ -> error("Unexpected dialog") })
        val denied = run(commands, "tile", "add", "my-tile")
        assertTrue(denied.failed)
        assertTrue(denied.lines.single().contains("отключено"))
        reopened.revoke(keys.fingerprint)
        assertFalse(store().trusted(record))
        store().trust(pem)
        assertFalse(store().trusted(record))
        assertThrows(PackageException::class.java) { store().install(preview) }
        assertTrue(run(commands, "dev", "info", "my-tile").lines.last().contains("Trust revoked"))
        assertFalse(run(commands, "dev", "remove", "my-tile").failed)
        assertNull(store().info("my-tile"))
    }

    @Test fun rejectsUnknownKeyBadSignatureAndChangedArchive() {
        val files = fixture()
        assertThrows(PackageException::class.java) { store().preview(files.first, files.second) }
        store().trust(pem)
        val bad = JSONObject(files.second.decodeToString()).put("signature", "00".repeat(64)).toString().encodeToByteArray()
        assertThrows(PackageException::class.java) { store().preview(files.first, bad) }
        assertThrows(PackageException::class.java) { store().preview(files.first + 0.toByte(), files.second) }
        assertThrows(PackageException::class.java) { store().preview(files.first, "{}".encodeToByteArray()) }
        assertTrue(store().packages().isEmpty())
    }

    @Test fun rejectsPrivateKeyCorruptPemAndUnsupportedAlgorithm() {
        assertThrows(PackageException::class.java) {
            store().trust("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----".encodeToByteArray())
        }
        assertThrows(PackageException::class.java) {
            store().trust("-----BEGIN PUBLIC KEY-----\nA\n-----END PUBLIC KEY-----".encodeToByteArray())
        }
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val encoded = Base64.getEncoder().encodeToString(rsa.public.encoded)
        assertThrows(PackageException::class.java) {
            store().trust("-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----".encodeToByteArray())
        }
        assertTrue(store().keys().isEmpty())
    }

    @Test fun rejectsOfficialIdCapabilitiesAndUnsafeZipMembers() {
        store().trust(pem)
        val badFiles = listOf(
            fixture(id = "hello-tile"),
            fixture(permissions = listOf("ui.tile", "network")),
            fixture(extra = "../main.lua" to byteArrayOf(1)),
            fixture(extra = "MAIN.LUA" to byteArrayOf(1)),
            fixture(extra = "bomb" to ByteArray(256 * 1024)),
        )
        for (file in badFiles) {
            assertThrows(PackageException::class.java) { store().preview(file.first, file.second) }
        }
        assertTrue(store().packages().isEmpty())
    }

    @Test fun cancellationAndInterruptedCommitLeaveNoVisiblePackage() = runBlocking {
        val store = store()
        val raw = fixture()
        val previewRead = CompletableDeferred<Unit>()
        val approve = CompletableDeferred<Boolean>()
        val cancelRead = DeveloperCommands(store, { null }, { _, _ -> true })
        assertEquals("Отменено.", run(cancelRead, "dev", "import").lines.single())
        val cancelTrust = DeveloperCommands(store, { pem }, { _, _ -> false })
        run(cancelTrust, "dev", "trust", "add")
        assertTrue(store.keys().isEmpty())
        store.trust(pem)
        val interrupted = DeveloperCommands(store, { limit ->
            if (limit == DeveloperPackageStore.ARCHIVE_LIMIT) raw.first else raw.second
        }, { title, _ ->
            if (title == "Импортировать пакет?") {
                previewRead.complete(Unit)
                approve.await()
            } else true
        })
        val pending = async { run(interrupted, "dev", "import") }
        previewRead.await()
        pending.cancelAndJoin()
        assertTrue(store.packages().isEmpty())
        val stale = folder.root.resolve("packages/.stage-crash").apply { mkdirs() }
        File(stale, "archive.hyp").writeBytes(raw.first)
        val denied = DeveloperCommands(store, { limit ->
            if (limit == DeveloperPackageStore.ARCHIVE_LIMIT) raw.first else raw.second
        }, { title, _ -> title != "Импортировать пакет?" })
        run(denied, "dev", "import")
        assertTrue(store.packages().isEmpty())
        assertFalse(stale.exists())
    }

    @Test fun sdkFixtureImportsThroughTheTerminalCommandContract() = runBlocking {
        val sdk = System.getenv("HYPR_DEVELOPER_SDK_FIXTURE")?.let(::File)
        val raw = if (sdk != null) {
            sdk.resolve("my-tile-1.0.0.hyp").readBytes() to sdk.resolve("my-tile.signature.json").readBytes()
        } else fixture()
        val publicKey = sdk?.resolve("developer.pub.pem")?.readBytes() ?: pem
        val queue = ArrayDeque(listOf(publicKey, raw.first, raw.second))
        val commands = DeveloperCommands(store(), { queue.removeFirst() }, { _, _ -> true })
        assertFalse(run(commands, "dev", "trust", "add").failed)
        assertFalse(run(commands, "dev", "import").failed)
        val reopened = DeveloperCommands(store(), { error("Unexpected SAF") }, { _, _ -> false })
        val info = run(reopened, "dev", "info", "my-tile")
        assertFalse(info.failed)
        assertTrue(info.lines.contains("Imported / Verified / Execution disabled"))
        assertTrue(run(reopened, "dev", "list").lines.single().startsWith("my-tile"))
    }

    private suspend fun run(commands: DeveloperCommands, vararg args: String) =
        ((commands.execute(args.toList()) as CommandResult.Stream).events.single() as CommandEvent.Operation).run()

    private fun fixture(
        id: String = "my-tile",
        permissions: List<String> = listOf("ui.tile", "state.local"),
        extra: Pair<String, ByteArray>? = null,
    ): Pair<ByteArray, ByteArray> {
        val manifest = JSONObject().put("id", id).put("version", "1.0.0").put("apiVersion", 2)
            .put("runtime", "lua").put("entry", "main.lua").put("permissions", JSONArray(permissions))
            .put("dependencies", JSONObject())
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val files = listOfNotNull("manifest.json" to manifest.toString().encodeToByteArray(),
                "main.lua" to "return nil".encodeToByteArray(), extra)
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val bytes = output.toByteArray()
        val signature = ByteArray(64)
        signingKey.sign(Ed25519.Algorithm.Ed25519, null, bytes, 0, bytes.size, signature, 0)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        val metadata = JSONObject().put("manifest", manifest).put("url", "https://example.invalid/test.hyp")
            .put("status", "active").put("sha256", hash.hex()).put("signature", signature.hex())
        return bytes to metadata.toString().encodeToByteArray()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
