package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DockOrderTest {
    @get:Rule val directory = TemporaryFolder()
    private val a = "example.a/.Main"
    private val b = "example.b/.Main"
    private val c = "example.c/.Main"
    @Test fun oldMembershipMigratesDeterministicallyAndRelativeOrderSurvivesRestart() = runBlocking {
        val file = directory.newFolder().resolve("shell.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            store.edit { it[stringSetPreferencesKey("favorites")] = setOf(b, a) }
            val repo = ShellRepository(store)
            assertEquals(listOf(a, b), repo.settings.first().dockComponents)
            repo.setWallpaper("content://keep")
            repo.setFavorite(c, true)
            repo.placeFavoriteBefore(c, a)
            repo.setFavorite(a, false)
            repo.placeFavoriteBefore(a, c) // Removed entries must not be resurrected by a late drop.
            assertEquals(listOf(c, b), repo.settings.first().dockComponents)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ShellRepository(PreferenceDataStoreFactory.create(scope = next, produceFile = { file }))
            assertEquals(listOf(c, b), repo.settings.first().dockComponents)
            assertEquals(setOf(c, b), repo.settings.first().favorites)
            assertEquals("content://keep", repo.settings.first().wallpaperUri)
            repo.removePackages(setOf("example.c"))
            assertEquals(listOf(b), repo.settings.first().dockComponents)
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun concurrentAddAndMoveRetainEveryPinAndUnknownOrderIsNotOverwritten() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { directory.newFolder().resolve("shell.preferences_pb") })
            val repo = ShellRepository(store)
            repo.setFavorite(a, true); repo.setFavorite(b, true)
            coroutineScope { launch { repo.setFavorite(c, true) }; launch { repo.placeFavoriteBefore(b, a) } }
            assertEquals(setOf(a, b, c), repo.settings.first().dockComponents.toSet())
            assertEquals(3, repo.settings.first().dockComponents.size)
            val key = stringPreferencesKey("favorites_order")
            val raw = "{\"version\":99,\"keep\":true}"
            store.edit { it[key] = raw }
            try { repo.setFavorite(c, false); fail() } catch (_: IOException) { }
            assertEquals(raw, store.data.first()[key])
            assertEquals(setOf(a, b, c), store.data.first()[stringSetPreferencesKey("favorites")])
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
