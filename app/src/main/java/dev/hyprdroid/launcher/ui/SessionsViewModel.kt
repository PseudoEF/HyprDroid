package dev.hyprdroid.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SessionsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkspaceRepository(application)
    private val catalog = LauncherCatalog(application)
    val error = MutableStateFlow<String?>(null)
    val apps = MutableStateFlow<List<LauncherApp>>(emptyList())
    val settings = repository.sessions.catch { error.value = it.message }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val events = repository.sessionEvents.catch { error.value = it.message }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val stopObserving = catalog.observe(::refresh) { refresh() }
    init { refresh() }
    fun refresh() { viewModelScope.launch(Dispatchers.IO) {
        runCatching { catalog.load() }.onSuccess { apps.value = it }.onFailure { error.value = "Каталог приложений недоступен" }
    } }
    fun enabled(value: Boolean) = update { copy(enabled = value) }
    fun assign(workspace: Int, component: String, value: Boolean) = update { assign(workspace, component, value) }
    private fun update(transform: WorkspaceSessions.() -> WorkspaceSessions) { viewModelScope.launch {
        runCatching { repository.setSessions { it.transform() } }.onFailure { error.value = it.message }
    } }
    fun record(event: SessionEvent) { viewModelScope.launch {
        runCatching { repository.recordSession(event) }.onFailure { error.value = "Не удалось сохранить результат попытки" }
    } }
    fun clearLog() { viewModelScope.launch { runCatching { repository.clearSessionEvents() }.onFailure { error.value = it.message } } }
    override fun onCleared() { stopObserving(); super.onCleared() }
}
