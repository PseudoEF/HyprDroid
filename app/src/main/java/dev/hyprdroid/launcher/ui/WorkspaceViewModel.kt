package dev.hyprdroid.launcher.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hyprdroid.launcher.data.CatalogRefreshGate
import dev.hyprdroid.launcher.data.CatalogRefresher
import dev.hyprdroid.launcher.data.DockFullException
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.data.LauncherCatalog
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.data.SidebarEntry
import dev.hyprdroid.launcher.modules.ModuleRegistry
import dev.hyprdroid.launcher.model.WorkspaceItem
import dev.hyprdroid.launcher.model.ShellPresentation
import dev.hyprdroid.launcher.model.ShellSettings
import dev.hyprdroid.launcher.model.WorkspaceSettings
import dev.hyprdroid.launcher.model.WorkspaceAppearance
import dev.hyprdroid.launcher.model.WorkspaceMoveConflictException
import dev.hyprdroid.launcher.workspaceengine.LayoutArea
import dev.hyprdroid.launcher.workspaceengine.SplitId
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import dev.hyprdroid.launcher.data.AnimatedWallpaperFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TILING_UNASSIGNED_MODULE_ID = ModuleRegistry.UNASSIGNED

internal data class WorkspaceUiState(
    val settings: WorkspaceSettings? = null,
    val shell: ShellSettings? = null,
    val apps: List<LauncherApp> = emptyList(),
    val sidebarEntries: List<SidebarEntry> = emptyList(),
    val loadingApps: Boolean = true,
    val catalogError: Boolean = false,
    val storageError: Boolean = false,
    val shellError: Boolean = false,
    val message: String? = null,
    internal val tiling: TilingState? = null,
)

internal class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val wallpaperOperations = AnimatedWallpaperFiles.operations
    private val repository = WorkspaceRepository(application)
    private val shellRepository = ShellRepository(application)
    private val catalog = LauncherCatalog(application)
    private val engineStore = WorkspaceEngineStore(application)
    private val engines = mutableMapOf<Int, WorkspaceEngine>()
    private val mutableState = MutableStateFlow(WorkspaceUiState())
    val state = mutableState.asStateFlow()
    private val catalogGate = CatalogRefreshGate()
    private val catalogRefresher = CatalogRefresher(catalogGate, SystemClock::elapsedRealtime, ::loadCatalog)
    private var storageJob: Job? = null
    private var shellJob: Job? = null
    private var engineJob: Job? = null
    private var stopObserving: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            try { wallpaperOperations.withLock {
                val keep = shellRepository.settings.first().wallpaperUri
                withContext(Dispatchers.IO) { AnimatedWallpaperFiles.cleanup(getApplication(), keep) }
            } } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { /* Unknown/unreadable stores must not trigger cleanup. */ }
        }
        retryStorage()
        retryShellStorage()
        engineJob = viewModelScope.launch {
            try {
                engineStore.load().forEach { (workspaceId, engine) ->
                    engines[workspaceId.toInt()] = engine
                    if (engineStore.isOpen(workspaceId)) openTilingSession(workspaceId.toInt(), engine)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* An unreadable engine store must not block the launcher. */ }
        }
        try {
            stopObserving = catalog.observe({ packages ->
                catalogGate.changed(packages)
                requestCatalog(force = false)
            }) { packageName ->
                removePackages(setOf(packageName))
                catalogGate.changed(setOf(packageName))
                requestCatalog(force = false)
            }
        } catch (_: RuntimeException) {
            showMessage("Не удалось включить обновление приложений. Список обновится при возврате на главный экран.")
        }
    }

    fun retryStorage() {
        storageJob?.cancel()
        storageJob = viewModelScope.launch {
            try {
                repository.settings.collect { settings ->
                    val firstLoad = mutableState.value.settings == null
                    mutableState.update { it.copy(settings = settings, storageError = false) }
                    if (firstLoad) requestCatalog(force = false)
                }
            } catch (_: IOException) {
                mutableState.update { it.copy(storageError = true) }
                showMessage("Не удалось прочитать пространства. Повторите чтение через меню панели.")
            }
        }
    }

    fun retryShellStorage() {
        shellJob?.cancel()
        shellJob = viewModelScope.launch {
            try {
                shellRepository.settings.collect { shell ->
                    val firstLoad = mutableState.value.shell == null
                    mutableState.update { it.copy(shell = shell, shellError = false) }
                    if (firstLoad) requestCatalog(force = false)
                }
            } catch (_: IOException) {
                mutableState.update { it.copy(shellError = true) }
                showMessage("Не удалось прочитать настройки панелей и обои. Повторите чтение через меню.")
            }
        }
    }

    /** Explicit request from the menu or a failed launch: bypasses the safety interval and re-decodes icons. */
    fun refreshApps() = requestCatalog(force = true)

    /** Returning to Home re-reads the catalogue only for a package event or the safety interval. */
    fun refreshAppsIfNeeded() = requestCatalog(force = false)

    private fun requestCatalog(force: Boolean) {
        viewModelScope.launch {
            catalogRefresher.refresh(hasApps = { mutableState.value.apps.isNotEmpty() }, force = force)
        }
    }

    /**
     * The only visible change on a background pass is that [WorkspaceUiState.loadingApps] stays
     * false: the already loaded catalogue remains on screen. A cold start keeps the old behaviour.
     */
    private suspend fun loadCatalog(invalidate: Set<String>, refreshIcons: Boolean): Boolean {
        if (mutableState.value.apps.isEmpty()) mutableState.update { it.copy(loadingApps = true) }
        return try {
            val pins = mutableState.value.settings?.workspaces?.flatMap { it.pinnedApps }?.toSet().orEmpty() +
                mutableState.value.shell?.favorites.orEmpty() + mutableState.value.shell?.sidebar.orEmpty()
                    .filterIsInstance<WorkspaceItem.AppShortcut>().map { it.componentId }
            val (apps, entries, missing) = withContext(Dispatchers.IO) {
                val snapshot = catalog.reload(invalidate, refreshIcons)
                val visiblePackages = snapshot.apps.map { it.componentId.substringBefore('/') }.toSet()
                Triple(snapshot.apps, snapshot.entries,
                    catalog.missingPackages(pins.filterNot { it.substringBefore('/') in visiblePackages }.toSet()))
            }
            mutableState.update { it.copy(apps = apps, sidebarEntries = entries, loadingApps = false, catalogError = false) }
            if (missing.isNotEmpty()) removePackages(missing)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            mutableState.update { it.copy(loadingApps = false, catalogError = true) }
            showMessage("Не удалось загрузить приложения. Повторите попытку через меню панели.")
            false
        }
    }

    private fun removePackages(packages: Set<String>) {
        save { repository.removePackages(packages) }
        save { shellRepository.removePackages(packages) }
    }

    fun setPinned(workspace: Int, component: String, pinned: Boolean) = save {
        repository.setPinned(workspace, component, pinned)
    }
    fun selectWorkspace(id: Int) = save { repository.selectWorkspace(id) }
    fun setGridLayout(workspace: Int, enabled: Boolean) = save { repository.setGridLayout(workspace, enabled) }
    fun setAppearance(workspace: Int, appearance: WorkspaceAppearance) = save { repository.setAppearance(workspace, appearance) }
    fun reorder(workspace: Int, id: String, delta: Int) = save { repository.reorder(workspace, id, delta) }
    fun placeBefore(workspace: Int, id: String, before: String?, onComplete: () -> Unit) = save(onComplete) { repository.placeBefore(workspace, id, before) }
    fun setSidebarItem(item: WorkspaceItem.AppShortcut, pinned: Boolean) = save { shellRepository.setSidebarItem(item, pinned) }
    fun removeSidebarItem(id: String) = save { shellRepository.removeSidebarItem(id) }
    fun reorderSidebar(id: String, delta: Int) = save { shellRepository.reorderSidebar(id, delta) }
    fun placeSidebarBefore(id: String, before: String?, onComplete: () -> Unit) = save(onComplete) { shellRepository.placeSidebarBefore(id, before) }
    fun launchSidebar(item: WorkspaceItem.AppShortcut) {
        try { catalog.launch(item) }
        catch (_: RuntimeException) { showMessage("Ярлык недоступен. Обновите каталог или уберите его из Sidebar."); refreshApps() }
    }
    fun moveItem(from: Int, to: Int, id: String) = save { repository.moveItem(from, to, id) }
    fun removeItem(workspace: Int, id: String) = save { repository.removeItem(workspace, id) }
    fun setFavorite(component: String, enabled: Boolean) = save { shellRepository.setFavorite(component, enabled) }
    fun placeFavoriteBefore(component: String, before: String?) = save { shellRepository.placeFavoriteBefore(component, before) }
    fun setPresentation(value: ShellPresentation, onComplete: () -> Unit) = save(onComplete) { shellRepository.setPresentation(value) }

    fun chooseWallpaper(uri: Uri) {
        viewModelScope.launch {
            wallpaperOperations.withLock {
            var previous: String? = null
            var saved = false
            var imported: Uri? = null
            var staticGranted = false
            try {
                previous = shellRepository.settings.first().wallpaperUri
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        if (AnimatedWallpaperFiles.isGif(getApplication(), uri)) {
                            imported = AnimatedWallpaperFiles.importGif(getApplication(), uri)
                        } else {
                            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            staticGranted = true
                            decodeWallpaper(getApplication(), uri, maxDimension = 64).recycle()
                        }
                    }
                    changeWallpaper((imported ?: uri).toString())
                    saved = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                showMessage("Не удалось открыть фон. GIF: до 24 МиБ, 8 Мп и 8192 px по стороне. Предыдущий фон сохранён.")
            } catch (_: OutOfMemoryError) {
                showMessage("Недостаточно памяти для этого изображения. Выберите файл меньшего размера.")
            } catch (_: RuntimeException) {
                showMessage("Изображение недоступно. Выберите другой файл.")
            } finally {
                if (!saved) imported?.let { AnimatedWallpaperFiles.remove(getApplication(), it) }
                if (!saved && staticGranted && uri.toString() != previous) releaseImagePermission(uri)
            }
            }
        }
    }

    fun useSystemWallpaper() = save { wallpaperOperations.withLock { changeWallpaper(null) } }

    private suspend fun changeWallpaper(uri: String?) {
        val previous = shellRepository.settings.first().wallpaperUri
        shellRepository.setWallpaper(uri)
        if (previous != null && previous != uri) {
            withContext(Dispatchers.IO) { AnimatedWallpaperFiles.remove(getApplication(), Uri.parse(previous)) }
            releaseImagePermission(Uri.parse(previous))
        }
    }

    private fun releaseImagePermission(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: RuntimeException) { /* The provider may have already revoked access. */ }
    }

    private fun save(onComplete: () -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (_: DockFullException) {
                showMessage("В dock помещаются четыре избранных приложения. Сначала уберите одно из них.")
            } catch (_: WorkspaceMoveConflictException) {
                showMessage("В целевом пространстве есть другой элемент с тем же ID. Перенос отменён; данные сохранены.")
            } catch (_: IOException) {
                showMessage("Изменения не сохранены. Проверьте свободное место и повторите попытку.")
            } finally { onComplete() }
        }
    }

    private fun openTilingSession(workspaceId: Int, engine: WorkspaceEngine) {
        mutableState.update { current ->
            val tiling = current.tiling
            if (tiling != null && tiling.open && tiling.workspaceId == workspaceId) current
            else current.copy(tiling = TilingState(open = true, workspaceId = workspaceId, focusedWindowId = engine.focusedWindowId, engine = engine.snapshot()))
        }
        save { engineStore.save(workspaceId.toString(), engine) }
    }

    private fun publishTiling(workspaceId: Int, engine: WorkspaceEngine) {
        mutableState.update { current ->
            val tiling = current.tiling
            if (tiling == null || !tiling.open || tiling.workspaceId != workspaceId) current
            else current.copy(tiling = TilingState(open = true, workspaceId = workspaceId, focusedWindowId = engine.focusedWindowId, engine = engine.snapshot()))
        }
    }

    /** Opens the internal tiling surface for one workspace; an empty tree receives a neutral tile. */
    fun openTilingSurface(workspaceId: Int) = viewModelScope.launch {
        try {
            val engine = engines.getOrPut(workspaceId) { WorkspaceEngine() }
            if (engine.root == null) engine.addFirstWindow(WorkspaceWindow(WindowId("tile-1"), TILING_UNASSIGNED_MODULE_ID, 0, 0))
            save { engineStore.save(workspaceId.toString(), engine) }
            openTilingSession(workspaceId, engine)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { showMessage("Не удалось открыть окно. Освободите память и попробуйте снова.") }
    }

    /** Closes the tiling surface only; the placement tree is kept for the next open. */
    fun closeTilingSurface() {
        mutableState.update { it.copy(tiling = null) }
    }

    /** Splits the focused internal window with a fresh neutral window using the adaptive layout. */
    internal fun addTilingWindow(
        workspaceId: Int,
        area: LayoutArea,
        densityScale: Float,
        targetWindowId: WindowId? = null,
    ) = viewModelScope.launch {
        try {
            val engine = engines.getOrPut(workspaceId) { WorkspaceEngine() }
            val target = targetWindowId
                ?: engine.focusedWindowId
                ?: engine.leaves().firstOrNull()
                ?: return@launch
            if (target !in engine.leaves()) return@launch
            val newWindowId = WindowId(UUID.randomUUID().toString())
            val splitId = SplitId(UUID.randomUUID().toString())
            val mins = engine.windowsById().mapValues { (_, window) -> tilingWindowMin(window, densityScale) }
            val window = WorkspaceWindow(newWindowId, TILING_UNASSIGNED_MODULE_ID, 0, 0)
            if (!engine.addWindowAdaptive(target, splitId, window, area, mins)) return@launch
            engine.focusWindow(newWindowId)
            save { engineStore.save(workspaceId.toString(), engine) }
            publishTiling(workspaceId, engine)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { showMessage("Не удалось добавить окно. Проверьте место в памяти.") }
    }

    /** Rebuilds the adaptive split topology when the workspace area or its orientation changes. */
    internal fun reflowTiling(workspaceId: Int, area: LayoutArea) {
        val engine = engines[workspaceId] ?: return
        if (!engine.reflow(area)) return
        save { engineStore.save(workspaceId.toString(), engine) }
        publishTiling(workspaceId, engine)
    }

    /** Removes one internal window; the surface closes itself when the tree becomes empty. */
    internal fun closeTilingWindow(workspaceId: Int, windowId: WindowId) = viewModelScope.launch {
        try {
            val engine = engines[workspaceId] ?: return@launch
            if (!engine.removeWindow(windowId)) return@launch
            save { engineStore.save(workspaceId.toString(), engine) }
            if (engine.root == null) mutableState.update { it.copy(tiling = null) }
            else publishTiling(workspaceId, engine)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { showMessage("Не удалось закрыть окно. Проверьте место в памяти.") }
    }

    internal fun focusTilingWindow(workspaceId: Int, windowId: WindowId) {
        val engine = engines[workspaceId] ?: return
        if (!engine.focusWindow(windowId)) return
        save { engineStore.save(workspaceId.toString(), engine) }
        publishTiling(workspaceId, engine)
    }

    /** Rebinds one window to another built-in module; the split tree and tile geometry stay intact. */
    internal fun setTilingModule(workspaceId: Int, windowId: WindowId, moduleId: String) = viewModelScope.launch {
        try {
            val engine = engines[workspaceId] ?: return@launch
            if (!engine.setWindowModule(windowId, moduleId)) return@launch
            save { engineStore.save(workspaceId.toString(), engine) }
            publishTiling(workspaceId, engine)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { showMessage("Не удалось сохранить модуль окна. Проверьте свободное место.") }
    }

    internal fun resizeTilingSplit(workspaceId: Int, splitId: SplitId, ratio: Float) {
        val engine = engines[workspaceId] ?: return
        if (!engine.resizeSplit(splitId, ratio)) return
        save { engineStore.save(workspaceId.toString(), engine) }
        publishTiling(workspaceId, engine)
    }

    fun launch(component: String) {
        try {
            catalog.launch(component)
        } catch (_: RuntimeException) {
            showMessage("Приложение недоступно или его запуск запрещён. Закрепление можно убрать через меню приложения.")
            refreshApps()
        }
    }

    fun showMessage(message: String) { mutableState.update { it.copy(message = message) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    override fun onCleared() {
        stopObserving?.invoke()
        engineJob?.cancel()
        super.onCleared()
    }
}
