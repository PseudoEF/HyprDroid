package dev.hyprdroid.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hyprdroid.launcher.model.WORKSPACE_COUNT
import dev.hyprdroid.launcher.model.WorkspaceAppearance
import dev.hyprdroid.launcher.model.WorkspaceItem
import dev.hyprdroid.launcher.model.ShellPresentation
import dev.hyprdroid.launcher.model.DesktopMode
import dev.hyprdroid.launcher.model.DockVisibility
import dev.hyprdroid.launcher.model.PanelSide
import dev.hyprdroid.launcher.model.PanelsSettings
import kotlinx.coroutines.delay
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
                chooseWallpaper = {
                    try { photoPicker.launch(arrayOf("image/*")) }
                    catch (_: RuntimeException) { model.showMessage("Системный выбор изображений недоступен.") }
                }, systemWallpaper = model::useSystemWallpaper,
                otherLauncher = { if (!openHomeSettings(context)) model.showMessage("Откройте настройки Android и выберите другой домашний экран.") },
                retryApps = model::refreshApps, retryWorkspace = model::retryStorage, retryShell = model::retryShellStorage,
                reorder = model::reorder, moveItem = model::moveItem, removeItem = model::removeItem,
                appearance = model::setAppearance,
                placeBefore = model::placeBefore, sidebarPin = model::setSidebarItem,
                sidebarRemove = model::removeSidebarItem, sidebarReorder = model::reorderSidebar,
                sidebarLaunch = model::launchSidebar,
                sidebarPlaceBefore = model::placeSidebarBefore,
                presentation = model::setPresentation,
                systemSettings = { action -> if (!openSystemSettings(context, action)) model.showMessage("Системные настройки недоступны.") },
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
    val gridLayout: (Int, Boolean) -> Unit,
    val chooseWallpaper: () -> Unit,
    val systemWallpaper: () -> Unit,
    val otherLauncher: () -> Unit,
    val retryApps: () -> Unit,
    val retryWorkspace: () -> Unit,
    val retryShell: () -> Unit,
    val reorder: (Int, String, Int) -> Unit,
    val moveItem: (Int, Int, String) -> Unit,
    val removeItem: (Int, String) -> Unit,
    val appearance: (Int, WorkspaceAppearance) -> Unit,
    val systemSettings: (String) -> Unit,
    val placeBefore: (Int, String, String?, () -> Unit) -> Unit,
    val sidebarPin: (WorkspaceItem.AppShortcut, Boolean) -> Unit,
    val sidebarRemove: (String) -> Unit,
    val sidebarReorder: (String, Int) -> Unit,
    val sidebarLaunch: (WorkspaceItem.AppShortcut) -> Unit,
    val sidebarPlaceBefore: (String, String?, () -> Unit) -> Unit,
    val presentation: (ShellPresentation, () -> Unit) -> Unit,
)

@Composable
fun ShellHome(state: WorkspaceUiState, actions: ShellActions) {
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    // Construct the pager only after preferences load, so its initial page is the persisted one.
    if (state.settings == null) {
        BackHandler(aboutOpen) { aboutOpen = false }
        if (aboutOpen) { AboutScreen { aboutOpen = false }; return }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Waybar(0, {}, listOf(
                AppMenuAction("О приложении") { aboutOpen = true },
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
    var controlOpen by rememberSaveable { mutableStateOf(false) }
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }
    var leftPanelOpen by rememberSaveable { mutableStateOf(false) }
    val panels = state.shell?.panels ?: PanelsSettings()
    var overlaySettingsOpen by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragRevision by remember { mutableIntStateOf(0) }
    var appearanceWorkspace by rememberSaveable { mutableStateOf<Int?>(null) }
    var orderWorkspace by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectingWorkspace by rememberSaveable { mutableStateOf<Int?>(null) }
    var presentationPreview by remember { mutableStateOf<ShellPresentation?>(null) }
    var savingPresentation by remember { mutableStateOf(false) }
    var autoDockShown by remember { mutableStateOf(true) }
    var dockPulse by remember { mutableIntStateOf(0) }
    // Do not flash Classic pins while a saved Clean preference is still loading.
    val presentation = presentationPreview ?: state.shell?.presentation ?: ShellPresentation(if (state.shellError) DesktopMode.CLASSIC else DesktopMode.CLEAN)
    val classic = presentation.desktopMode == DesktopMode.CLASSIC
    val closePresentation = { presentationPreview = null; savingPresentation = false }
    val openPresentation = { if (state.shell != null && !state.shellError) { editing = false; presentationPreview = state.shell.presentation } }
    val revealDock: () -> Unit = { autoDockShown = true; dockPulse++ }
    val appsById = remember(state.apps) { state.apps.associateBy { it.componentId } }
    val favorites = state.shell?.favorites.orEmpty()
    val workspaceReady = !state.storageError
    val shellReady = state.shell != null && !state.shellError
    val enterEdit = {
        if (!classic) openPresentation()
        else if (workspaceReady) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            editing = true
        }
    }
    val openDrawer = { controlOpen = false; selectingWorkspace = null; drawerOpen = true }
    val openControl = { selectingWorkspace = null; drawerOpen = false; controlOpen = true }
    val launchApp: (String) -> Unit = { component -> drawerOpen = false; actions.launch(component) }
    val closeDrawer = { drawerOpen = false; selectingWorkspace = null }
    val itemMenu: (Int, WorkspaceItem) -> List<AppMenuAction> = { workspace, item ->
        val items = settings.workspaces[workspace].items
        val index = items.indexOfFirst { it.id == item.id }
        buildList {
            if (index >= 0) {
                add(AppMenuAction("Раньше", workspaceReady && index > 0) { actions.reorder(workspace, item.id, -1) })
                add(AppMenuAction("Позже", workspaceReady && index < items.lastIndex) { actions.reorder(workspace, item.id, 1) })
                repeat(WORKSPACE_COUNT) { target ->
                    if (target != workspace) add(AppMenuAction("Переместить в пространство 0${target + 1}", workspaceReady) { actions.moveItem(workspace, target, item.id) })
                }
            }
        }
    }
    val appMenuForWorkspace: (Int, String) -> List<AppMenuAction> = { workspace, component ->
        val pinned = component in settings.workspaces[workspace].pinnedApps
        listOf(
            AppMenuAction(if (pinned) "Убрать из пространства" else "Закрепить в пространстве 0${workspace + 1}", workspaceReady) { actions.pin(workspace, component, !pinned) },
            AppMenuAction(if (component in favorites) "Убрать из dock" else "Добавить в dock", shellReady) { actions.favorite(component, component !in favorites) },
        ) + itemMenu(workspace, WorkspaceItem.AppShortcut(component))
    }
    val appMenu: (String) -> List<AppMenuAction> = { component ->
        appMenuForWorkspace(selectingWorkspace ?: pager.currentPage, component)
    }
    LaunchedEffect(pager, workspaceReady) {
        if (workspaceReady) snapshotFlow { pager.settledPage }.distinctUntilChanged().collect(actions.selectWorkspace)
    }
    val overlayOpen = leftPanelOpen || overlaySettingsOpen || drawerOpen || controlOpen || sidebarOpen || aboutOpen || presentationPreview != null || appearanceWorkspace != null || orderWorkspace != null
    LaunchedEffect(panels) {
        if (!panels.left.scope.home) leftPanelOpen = false
        if (!panels.right.scope.home) sidebarOpen = false
    }
    LaunchedEffect(presentation.desktopMode) { if (!classic) editing = false }
    LaunchedEffect(pager.settledPage) { revealDock() }
    val homeLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(homeLifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revealDock() }
        homeLifecycle.addObserver(observer)
        onDispose { homeLifecycle.removeObserver(observer) }
    }
    LaunchedEffect(presentation.dockVisibility, autoDockShown, dockPulse, editing, overlayOpen) {
        if (presentation.dockVisibility == DockVisibility.AUTO && autoDockShown && !editing && !overlayOpen) {
            delay(3_500); autoDockShown = false
        }
    }
    BackHandler(enabled = overlayOpen || editing) {
        when {
            leftPanelOpen -> leftPanelOpen = false
            overlaySettingsOpen -> overlaySettingsOpen = false
            presentationPreview != null -> if (!savingPresentation) closePresentation()
            aboutOpen -> aboutOpen = false
            sidebarOpen -> sidebarOpen = false
            drawerOpen -> closeDrawer()
            controlOpen -> controlOpen = false
            appearanceWorkspace != null -> appearanceWorkspace = null
            orderWorkspace != null -> orderWorkspace = null
            else -> editing = false
        }
    }
    Box(Modifier.fillMaxSize().testTag("home-panel-root")
        .homePanelGesture(PanelSide.LEFT, panels.left, !overlayOpen && !editing && panels.left.scope.home) { leftPanelOpen = true }
        .homePanelGesture(PanelSide.RIGHT, panels.right, !overlayOpen && !editing && panels.right.scope.home) { sidebarOpen = true }) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)
            .then(if (overlayOpen) Modifier.clearAndSetSemantics {} else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val panelMenu = buildList {
            add(AppMenuAction("Рабочий стол и панели", shellReady, openPresentation))
            add(AppMenuAction("О приложении") { aboutOpen = true })
            add(AppMenuAction("Все приложения", onClick = openDrawer))
            add(AppMenuAction("Control Center", onClick = openControl))
            add(AppMenuAction("Sidebar", panels.right.scope.home) { sidebarOpen = true })
            add(AppMenuAction("Левая панель", panels.left.scope.home) { leftPanelOpen = true })
            add(AppMenuAction("Настройки панелей", shellReady) { overlaySettingsOpen = true })
            if (presentation.dockVisibility == DockVisibility.AUTO) add(AppMenuAction("Показать dock", onClick = revealDock))
            add(AppMenuAction("Редактировать пространство", workspaceReady, enterEdit))
            add(AppMenuAction("Вид пространства", workspaceReady) { appearanceWorkspace = pager.currentPage })
            add(AppMenuAction("Порядок элементов", workspaceReady) { orderWorkspace = pager.currentPage })
            add(AppMenuAction("Выбрать изображение…", shellReady, actions.chooseWallpaper))
            add(AppMenuAction("Системные обои", shellReady, actions.systemWallpaper))
            add(AppMenuAction("Другой лаунчер", onClick = actions.otherLauncher))
            if (state.catalogError) add(AppMenuAction("Обновить приложения", onClick = actions.retryApps))
            if (state.storageError) add(AppMenuAction("Повторить чтение пространств", onClick = actions.retryWorkspace))
            if (state.shellError) add(AppMenuAction("Повторить чтение панелей и обоев", onClick = actions.retryShell))
        }
        Waybar(pager.currentPage, { index -> scope.launch { pager.animateScrollToPage(index) }; Unit }, panelMenu, Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            onDrawer = openDrawer, onSidebar = { if (panels.right.scope.home) sidebarOpen = true else overlaySettingsOpen = true },
            onLeftPanel = { if (panels.left.scope.home) leftPanelOpen = true else overlaySettingsOpen = true })
        Spacer(Modifier.height(12.dp))
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).widthIn(max = 960.dp).fillMaxWidth().testTag("home-pager")
            .sidebarOpeningGesture(!overlayOpen && !editing && panels.right.scope.home, { sidebarOpen = true }), userScrollEnabled = !overlayOpen && !dragging) { page ->
            if (!classic) Box(Modifier.fillMaxSize().testTag("workspace-canvas-$page")) {
                Box(Modifier.fillMaxSize().testTag("clean-workspace-$page")
                    .homeBlankGestures(!overlayOpen, true, openPresentation, openDrawer, openControl, revealDock))
            }
            else WorkspaceCanvas(
                settings.workspaces[page], appsById, editing && workspaceReady,
                gesturesEnabled = !overlayOpen, onEdit = enterEdit, onSwipeUp = openDrawer, onSwipeDown = openControl,
                onLaunch = launchApp,
                onRemove = { if (workspaceReady) actions.removeItem(page, it) },
                menu = { item -> if (item is WorkspaceItem.AppShortcut) appMenuForWorkspace(page, item.componentId) else
                    listOf(AppMenuAction("Убрать элемент", workspaceReady) { actions.removeItem(page, item.id) }) + itemMenu(page, item) },
                modifier = Modifier.fillMaxSize(),
                onDrop = { id, before -> if (workspaceReady) actions.placeBefore(page, id, before) { dragRevision++ } },
                onDragging = { dragging = it },
                resetToken = dragRevision,
                onBlankTap = revealDock,
            )
        }
        if (editing && classic && workspaceReady) {
            Spacer(Modifier.height(8.dp))
            EditToolbar(pager.currentPage, settings.workspaces[pager.currentPage].appearance.gridLayout,
                onAdd = { selectingWorkspace = pager.currentPage; drawerOpen = true },
                onLayout = { actions.gridLayout(pager.currentPage, !settings.workspaces[pager.currentPage].appearance.gridLayout) }, onDone = { editing = false },
                onOrder = { orderWorkspace = pager.currentPage }, onAppearance = { appearanceWorkspace = pager.currentPage },
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth())
        }
        AnimatedVisibility(presentation.dockVisibility != DockVisibility.HIDDEN &&
            (presentation.dockVisibility == DockVisibility.ALWAYS || autoDockShown || editing)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag("favorite-dock")) {
                Spacer(Modifier.height(12.dp))
                FavoriteDock(favorites.sortedBy { appsById[it]?.label ?: it }, appsById, launchApp, appMenu, openDrawer)
            }
        }
    }
    if (!overlayOpen && !editing) HomePanelMarkers(panels)
    AnimatedVisibility(visible = leftPanelOpen, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        ModularSidebar(state, onClose = { leftPanelOpen = false }, onLaunch = actions.sidebarLaunch,
            onPin = actions.sidebarPin, onRemove = actions.sidebarRemove, onReorder = actions.sidebarReorder,
            onPlaceBefore = actions.sidebarPlaceBefore, side = PanelSide.LEFT,
            panelModifier = Modifier.animateEnterExit(enter = slideInHorizontally(tween(240)) { -it }, exit = slideOutHorizontally(tween(200)) { -it }))
    }
    AnimatedVisibility(visible = sidebarOpen, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        ModularSidebar(state, onClose = { sidebarOpen = false },
            onLaunch = { sidebarOpen = false; actions.sidebarLaunch(it) }, onPin = actions.sidebarPin,
            onRemove = actions.sidebarRemove, onReorder = actions.sidebarReorder,
            onPlaceBefore = actions.sidebarPlaceBefore,
            panelModifier = Modifier.animateEnterExit(enter = slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it },
                exit = slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it }))
    }
    AnimatedVisibility(visible = drawerOpen, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
        AppDrawer(state, selectingWorkspace, closeDrawer, launchApp,
            onPin = { component, enabled -> selectingWorkspace?.let { actions.pin(it, component, enabled) } },
            menu = appMenu, onRetry = actions.retryApps)
    }
    if (controlOpen) ControlCenter(onClose = { controlOpen = false }, onSettings = actions.systemSettings)
    if (aboutOpen) AboutScreen { aboutOpen = false }
    if (overlaySettingsOpen) OverlaySettingsPanel { overlaySettingsOpen = false }
    presentationPreview?.let { draft -> ShellAppearancePanel(draft, savingPresentation,
        onChange = { presentationPreview = it }, onCancel = closePresentation,
        onApply = { savingPresentation = true; actions.presentation(draft, closePresentation) }) }
    appearanceWorkspace?.let { id ->
        val workspace = settings.workspaces[id]
        WorkspaceAppearanceScreen(workspace, workspace.pinnedApps.firstOrNull()?.let { appsById[it] },
            onApply = { if (workspaceReady) actions.appearance(id, it); appearanceWorkspace = null },
            onClose = { appearanceWorkspace = null })
    }
    orderWorkspace?.let { id ->
        WorkspaceOrderScreen(settings.workspaces[id], appsById,
            onReorder = { item, delta -> if (workspaceReady) actions.reorder(id, item, delta) }, onClose = { orderWorkspace = null })
    }
    }
}
