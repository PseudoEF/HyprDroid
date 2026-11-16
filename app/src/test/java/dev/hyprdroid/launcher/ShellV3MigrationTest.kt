package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellV3MigrationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun freshInstallStaysCleanAfterWritesAndRestartEvenWhenWorkspaceNowExists() = runBlocking {
        val file = directory.newFolder().resolve("shell.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = first, migrations = listOf(ShellV3Migration(false)), produceFile = { file }))
            assertEquals(DesktopMode.CLEAN, repository.settings.first().presentation.desktopMode)
            repository.setFavorite("example.app/.Main", true)
            repository.setSidebarItem(WorkspaceItem.AppShortcut("example.app/.Main"), true)
        } finally { first.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = next, migrations = listOf(ShellV3Migration(true)), produceFile = { file }))
            assertEquals(DesktopMode.CLEAN, repository.settings.first().presentation.desktopMode)
            assertEquals(setOf("example.app/.Main"), repository.settings.first().favorites)
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun legacyVersionsAndEmptyLegacyWorkspaceBecomeClassicWithoutChangingRecords() = runBlocking {
        for (version in 1..2) {
            val legacy = mutablePreferencesOf(intPreferencesKey("schema_version") to version,
                stringSetPreferencesKey("favorites") to setOf("example.app/.Main"),
                stringPreferencesKey("wallpaper_uri") to "content://image/kept",
                stringPreferencesKey("sidebar_items") to "[{\"id\":\"future:one\",\"type\":\"future\",\"payload\":\"keep\"}]",
                stringPreferencesKey("unknown_field") to "unchanged")
            val migration = ShellV3Migration(false)
            val migrated = migration.migrate(legacy)
            for ((key, value) in legacy.asMap()) if (key.name != "schema_version") assertEquals(value, migrated.asMap()[key])
            assertEquals(DesktopMode.CLASSIC, ShellPresentationCodec.read(migrated).desktopMode)
            assertEquals(DockVisibility.ALWAYS, ShellPresentationCodec.read(migrated).dockVisibility)
            assertFalse(migration.shouldMigrate(migrated))
        }
        assertEquals(DesktopMode.CLASSIC, ShellPresentationCodec.read(ShellV3Migration(true).migrate(emptyPreferences())).desktopMode)
        assertEquals(DesktopMode.CLASSIC, ShellPresentationCodec.read(ShellV3Migration(false).migrate(mutablePreferencesOf(intPreferencesKey("schema_version") to 2))).desktopMode)
    }

    @Test fun modesDockAndModulesPersistIndependentlyOfPinsAndUnknownItems() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = directory.newFolder().resolve("shell.preferences_pb")
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = scope, migrations = listOf(ShellV3Migration(false)), produceFile = { file }))
            val item = WorkspaceItem.AppShortcut("example.app/.Main")
            repository.setSidebarItem(item, true)
            repository.setFavorite(item.componentId, true)
            repository.setWallpaper("content://image")
            val before = repository.settings.first()
            for (mode in DesktopMode.entries) for (visibility in DockVisibility.entries) {
                val presentation = ShellPresentation(mode, visibility, setOf(SidebarModule.SYSTEM_STATUS))
                repository.setPresentation(presentation)
                val after = repository.settings.first()
                assertEquals(before.copy(presentation = presentation), after)
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun unknownSchemaOrMalformedModeIsNotSilentlyReset() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = directory.newFolder().resolve("shell.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = scope, migrations = listOf(ShellV3Migration(false)), produceFile = { file })
            val repository = ShellRepository(store)
            repository.settings.first()
            for (schema in listOf(3, 4)) {
                store.edit { it[intPreferencesKey("schema_version")] = schema; it[stringPreferencesKey("desktop_mode")] = "future" }
                val before = store.data.first()
                try { repository.setFavorite("example.app/.Main", true); fail("Refuse incompatible data") } catch (_: IOException) { }
                assertEquals(before, store.data.first())
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
