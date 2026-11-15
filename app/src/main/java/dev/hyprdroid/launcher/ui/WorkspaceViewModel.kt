package dev.hyprdroid.launcher.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hyprdroid.launcher.data.DockFullException
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.data.LauncherCatalog
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.data.SidebarEntry
import dev.hyprdroid.launcher.model.WorkspaceItem
import dev.hyprdroid.launcher.model.ShellSettings
import dev.hyprdroid.launcher.model.WorkspaceSettings
import dev.hyprdroid.launcher.model.WorkspaceAppearance
import dev.hyprdroid.launcher.model.WorkspaceMoveConflictException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WorkspaceUiState(
    val settings: WorkspaceSettings? = null,
    val shell: ShellSettings? = null,
    val apps: List<LauncherApp> = emptyList(),
    val sidebarEntries: List<SidebarEntry> = emptyList(),
    val loadingApps: Boolean = true,
    val catalogError: Boolean = false,
    val storageError: Boolean = false,
    val shellError: Boolean = false,
    val message: String? = null,
)

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkspaceRepository(application)
    private val shellRepository = ShellRepository(application)
    private val catalog = LauncherCatalog(application)
    private val mutableState = MutableStateFlow(WorkspaceUiState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var storageJob: Job? = null
    private var shellJob: Job? = null
    private var stopObserving: (() -> Unit)? = null

    init {
        retryStorage()
        retryShellStorage()
        try {
            stopObserving = catalog.observe(::refreshApps) { packageName ->
                removePackages(setOf(packageName))
                refreshApps()
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
                    if (firstLoad) refreshApps()
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
                    if (firstLoad) refreshApps()
                }
            } catch (_: IOException) {
                mutableState.update { it.copy(shellError = true) }
                showMessage("Не удалось прочитать dock и обои. Повторите чтение через меню панели.")
            }
        }
    }

    fun refreshApps() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingApps = true) }
            try {
                val pins = mutableState.value.settings?.workspaces?.flatMap { it.pinnedApps }?.toSet().orEmpty() +
                    mutableState.value.shell?.favorites.orEmpty() + mutableState.value.shell?.sidebar.orEmpty()
                        .filterIsInstance<WorkspaceItem.AppShortcut>().map { it.componentId }
                val (apps, entries, missing) = withContext(Dispatchers.IO) {
                    val apps = catalog.load()
                    val visiblePackages = apps.map { it.componentId.substringBefore('/') }.toSet()
                    Triple(apps, catalog.loadSidebarEntries(apps), catalog.missingPackages(pins.filterNot { it.substringBefore('/') in visiblePackages }.toSet()))
                }
                mutableState.update { it.copy(apps = apps, sidebarEntries = entries, loadingApps = false, catalogError = false) }
                if (missing.isNotEmpty()) removePackages(missing)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                mutableState.update { it.copy(loadingApps = false, catalogError = true) }
                showMessage("Не удалось загрузить приложения. Повторите попытку через меню панели.")
            }
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
    fun launchSidebar(item: WorkspaceItem.AppShortcut) {
        try { catalog.launch(item) }
        catch (_: RuntimeException) { showMessage("Ярлык недоступен. Обновите каталог или уберите его из Sidebar."); refreshApps() }
    }
    fun moveItem(from: Int, to: Int, id: String) = save { repository.moveItem(from, to, id) }
    fun removeItem(workspace: Int, id: String) = save { repository.removeItem(workspace, id) }
    fun setFavorite(component: String, enabled: Boolean) = save { shellRepository.setFavorite(component, enabled) }

    fun chooseWallpaper(uri: Uri) {
        viewModelScope.launch {
            val previous = mutableState.value.shell?.wallpaperUri
            var saved = false
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    decodeWallpaper(getApplication(), uri, maxDimension = 64).recycle()
                }
                changeWallpaper(uri.toString())
                saved = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                showMessage("Не удалось открыть или сохранить изображение. Предыдущий фон сохранён.")
            } catch (_: RuntimeException) {
                showMessage("Изображение недоступно. Выберите другой файл.")
            } finally {
                if (!saved && uri.toString() != previous) releaseImagePermission(uri)
            }
        }
    }

    fun useSystemWallpaper() = save { changeWallpaper(null) }

    private suspend fun changeWallpaper(uri: String?) {
        val previous = mutableState.value.shell?.wallpaperUri
        shellRepository.setWallpaper(uri)
        if (previous != null && previous != uri) {
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
        super.onCleared()
    }
}
