package dev.hyprdroid.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.data.LauncherCatalog
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.model.WorkspaceSettings
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
    val apps: List<LauncherApp> = emptyList(),
    val loadingApps: Boolean = true,
    val catalogError: Boolean = false,
    val storageError: Boolean = false,
    val message: String? = null,
)

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkspaceRepository(application)
    private val catalog = LauncherCatalog(application)
    private val mutableState = MutableStateFlow(WorkspaceUiState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var storageJob: Job? = null
    private var stopObserving: (() -> Unit)? = null

    init {
        retryStorage()
        try {
            stopObserving = catalog.observe(::refreshApps) { packageName ->
                save { repository.removePackages(setOf(packageName)) }
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
            } catch (error: IOException) {
                mutableState.update { it.copy(storageError = true) }
                showMessage("Не удалось прочитать настройки. Проверьте свободное место и повторите попытку.")
            }
        }
    }

    fun refreshApps() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingApps = true) }
            try {
                val pins = mutableState.value.settings?.workspaces?.flatMap { it.pinnedApps }?.toSet().orEmpty()
                val (apps, missing) = withContext(Dispatchers.IO) {
                    catalog.load() to catalog.missingPackages(pins)
                }
                mutableState.update { it.copy(apps = apps, loadingApps = false, catalogError = false) }
                if (missing.isNotEmpty()) save { repository.removePackages(missing) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                mutableState.update { it.copy(loadingApps = false, catalogError = true) }
                showMessage("Не удалось загрузить приложения. Повторите попытку.")
            }
        }
    }

    fun setPinned(workspace: Int, component: String, pinned: Boolean) = save {
        repository.setPinned(workspace, component, pinned)
    }
    fun selectWorkspace(id: Int) = save { repository.selectWorkspace(id) }
    fun setGridLayout(enabled: Boolean) = save { repository.setGridLayout(enabled) }

    private fun save(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (_: IOException) {
                showMessage("Изменения не сохранены. Проверьте свободное место и повторите попытку.")
            }
        }
    }

    fun launch(component: String) {
        try {
            catalog.launch(component)
        } catch (_: RuntimeException) {
            showMessage("Приложение недоступно или его запуск запрещён. Можно удалить закрепление.")
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
