package dev.hyprdroid.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hyprdroid.launcher.model.WORKSPACE_COUNT
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun WorkspaceScreen(model: WorkspaceViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.chooseWallpaper(uri)
    }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshApps() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }
    ShellTheme {
        Box(Modifier.fillMaxSize()) {
            WallpaperLayer(state.shell?.wallpaperUri) { model.showMessage("Изображение недоступно. Показаны системные обои; выберите другой фон через меню.") }
            ShellHome(state, ShellActions(
                launch = model::launch, pin = model::setPinned, favorite = model::setFavorite,
                selectWorkspace = model::selectWorkspace, gridLayout = model::setGridLayout,
                chooseWallpaper = { photoPicker.launch(arrayOf("image/*")) }, systemWallpaper = model::useSystemWallpaper,
                otherLauncher = { if (!openHomeSettings(context)) model.showMessage("Откройте настройки Android и выберите другой домашний экран.") },
                retryApps = model::refreshApps, retryWorkspace = model::retryStorage, retryShell = model::retryShellStorage,
            ))
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding().padding(12.dp))
        }
    }
}

data class ShellActions(
    val launch: (String) -> Unit,
    val pin: (Int, String, Boolean) -> Unit,
    val favorite: (String, Boolean) -> Unit,
    val selectWorkspace: (Int) -> Unit,
    val gridLayout: (Boolean) -> Unit,
    val chooseWallpaper: () -> Unit,
    val systemWallpaper: () -> Unit,
    val otherLauncher: () -> Unit,
    val retryApps: () -> Unit,
    val retryWorkspace: () -> Unit,
    val retryShell: () -> Unit,
)

@Composable
fun ShellHome(state: WorkspaceUiState, actions: ShellActions) {
    // Construct the pager only after preferences load, so its initial page is the persisted one.
    if (state.settings == null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Waybar(0, {}, listOf(
                AppMenuAction("Повторить чтение пространств", onClick = actions.retryWorkspace),
                AppMenuAction("Другой лаунчер", onClick = actions.otherLauncher),
            ), Modifier.fillMaxWidth())
            if (!state.storageError) CircularProgressIndicator(Modifier.padding(32.dp))
        }
        return
    }
    val settings = state.settings
    val pager = rememberPagerState(initialPage = settings.selectedWorkspace, pageCount = { WORKSPACE_COUNT })
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var selectingWorkspace by rememberSaveable { mutableStateOf<Int?>(null) }
    val appsById = remember(state.apps) { state.apps.associateBy { it.componentId } }
    val favorites = state.shell?.favorites.orEmpty()
    val workspaceReady = !state.storageError
    val shellReady = state.shell != null && !state.shellError
    val enterEdit = {
        if (workspaceReady) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            editing = true
        }
    }
    val openDrawer = { selectingWorkspace = null; drawerOpen = true }
    val launchApp: (String) -> Unit = { component -> drawerOpen = false; actions.launch(component) }
    val closeDrawer = { drawerOpen = false; selectingWorkspace = null }
    val appMenu: (String) -> List<AppMenuAction> = { component ->
        val workspace = selectingWorkspace ?: pager.currentPage
        val pinned = component in settings.workspaces[workspace].pinnedApps
        listOf(
            AppMenuAction(if (pinned) "Убрать из пространства" else "Закрепить в пространстве 0${workspace + 1}", workspaceReady) { actions.pin(workspace, component, !pinned) },
            AppMenuAction(if (component in favorites) "Убрать из dock" else "Добавить в dock", shellReady) { actions.favorite(component, component !in favorites) },
        )
    }
    LaunchedEffect(pager, workspaceReady) {
        if (workspaceReady) snapshotFlow { pager.settledPage }.distinctUntilChanged().collect(actions.selectWorkspace)
    }
    BackHandler(enabled = drawerOpen || editing) {
        if (drawerOpen) closeDrawer() else editing = false
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val panelMenu = buildList {
            add(AppMenuAction("Все приложения", onClick = openDrawer))
            add(AppMenuAction("Редактировать пространство", workspaceReady, enterEdit))
            add(AppMenuAction("Выбрать изображение…", shellReady, actions.chooseWallpaper))
            add(AppMenuAction("Системные обои", shellReady, actions.systemWallpaper))
            add(AppMenuAction("Другой лаунчер", onClick = actions.otherLauncher))
            if (state.catalogError) add(AppMenuAction("Обновить приложения", onClick = actions.retryApps))
            if (state.storageError) add(AppMenuAction("Повторить чтение пространств", onClick = actions.retryWorkspace))
            if (state.shellError) add(AppMenuAction("Повторить чтение dock и обоев", onClick = actions.retryShell))
        }
        Waybar(pager.currentPage, { index -> scope.launch { pager.animateScrollToPage(index) }; Unit }, panelMenu, Modifier.widthIn(max = 760.dp).fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).widthIn(max = 960.dp).fillMaxWidth(), userScrollEnabled = !drawerOpen) { page ->
            WorkspaceCanvas(
                settings.workspaces[page], appsById, settings.gridLayout, editing && workspaceReady,
                onEdit = enterEdit, onLaunch = launchApp,
                onRemove = { if (workspaceReady) actions.pin(page, it, false) }, menu = appMenu,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (editing && workspaceReady) {
            Spacer(Modifier.height(8.dp))
            EditToolbar(pager.currentPage, settings.gridLayout,
                onAdd = { selectingWorkspace = pager.currentPage; drawerOpen = true },
                onLayout = { actions.gridLayout(!settings.gridLayout) }, onDone = { editing = false },
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        FavoriteDock(favorites.sortedBy { appsById[it]?.label ?: it }, appsById, launchApp, appMenu, openDrawer)
    }
    AnimatedVisibility(visible = drawerOpen, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
        AppDrawer(state, selectingWorkspace, closeDrawer, launchApp,
            onPin = { component, enabled -> selectingWorkspace?.let { actions.pin(it, component, enabled) } },
            menu = appMenu, onRetry = actions.retryApps)
    }
}
