package com.nanihadesuka.irisx.feature.home

import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.TransferState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

private fun libraryPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Camera("Camera", Icons.Rounded.CameraAlt),
    Library("Library", Icons.Rounded.PhotoLibrary),
    Settings("Settings", Icons.Rounded.Settings),
}

@OptIn(ExperimentalLayoutApi::class)
/** The four bottom-bar destinations. Settings is passed in because it lives in its own feature module. */
@Composable
fun MainShell(
    onPair: () -> Unit,
    settings: @Composable () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(Tab.Home.name) }
    val tab = Tab.valueOf(tabName)
    val tabStates = rememberSaveableStateHolder()
    // Each second while a step can stall, so the wait can be named as it grows.
    val ticking = ui.transfer !is TransferState.Idle && ui.transfer !is TransferState.Failed && ui.transfer !is TransferState.Browsing
    val now by produceState(System.currentTimeMillis(), ticking) {
        while (true) { value = System.currentTimeMillis(); delay(if (ticking) 1_000 else 30_000) }
    }
    val radios = rememberPhoneRadios()
    val status = linkStatus(ui.copy(bluetoothOff = radios.bluetoothOff, wifiOff = radios.wifiOff), now)
    // Whatever failed for want of a radio is tried again as soon as it is back.
    var radioWasOff by remember { mutableStateOf(false) }
    LaunchedEffect(radios) {
        val off = radios.bluetoothOff || radios.wifiOff
        if (radioWasOff && !off && ui.transfer is TransferState.Failed) viewModel.retry()
        radioWasOff = off
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onAppVisible()
        viewModel.onForeground()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onBackground() }
    LaunchedEffect(ui.camera, ui.settings.syncPaused) { viewModel.onAppVisible() }

    val context = LocalContext.current
    fun libraryGranted() = context.checkSelfPermission(libraryPermission()) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(libraryGranted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = libraryGranted() }
    val libraryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = libraryGranted()
        viewModel.refreshLibrary()
    }
    val hiddenPhotos = ui.loaded && ui.settings.libraryPredatesInstall && !granted
    LaunchedEffect(hiddenPhotos, ui.settings.libraryPrompted) {
        if (hiddenPhotos && !ui.settings.libraryPrompted) {
            viewModel.libraryPrompted()
            libraryLauncher.launch(libraryPermission())
        }
    }
    LaunchedEffect(granted) { if (granted) viewModel.refreshLibrary() }

    // Automatic compression must read the location to keep it. Without this it leaves photos as they are.
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(ui.loaded, ui.settings.compressOnDownload, ui.settings.compressOnBackup) {
        if (ui.loaded && (ui.settings.compressOnDownload || ui.settings.compressOnBackup) &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }

    val wakeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    // Not keyed on wakePrompted: wakeRequest sets it, and that must not cancel the wait for the system dialog.
    LaunchedEffect(ui.connection == ConnectionState.Connected) {
        if (ui.connection == ConnectionState.Connected && !ui.settings.wakePrompted) {
            viewModel.wakeRequest()?.let { wakeLauncher.launch(IntentSenderRequest.Builder(it).build()) }
        }
    }

    BackHandler(tab != Tab.Home) { tabName = Tab.Home.name }

    var viewerFromCamera by rememberSaveable { mutableStateOf(false) }
    var viewerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerFilter by rememberSaveable { mutableStateOf(MediaFilter.All) }
    val openPhone: (String, MediaFilter) -> Unit = { uri, filter -> viewerFromCamera = false; viewerKey = uri; viewerFilter = filter }
    val openCamera: (Int, MediaFilter) -> Unit = { handle, filter -> viewerFromCamera = true; viewerKey = handle.toString(); viewerFilter = filter }
    val messages = remember { AppMessages() }

    val photoSources = remember { mutableMapOf<String, PhotoSource>() }
    CompositionLocalProvider(LocalPhotoSources provides photoSources, LocalAppMessages provides messages) {
    val turnOn = rememberTurnOnRadio()
    val statusAction: (LinkStatus) -> Unit = { shown ->
        when {
            shown.radio != null -> turnOn(shown.radio)
            shown.retryLabel == "Connect" -> viewModel.connectCamera()
            else -> viewModel.retry()
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // Ignoring visibility: hiding the status bar for immersive view must not move the screen beneath it.
            Column(Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)) {
                AnimatedVisibility(
                    visible = tab != Tab.Home && status.noteworthy,
                    enter = expandVertically(tween(FADE_MS)) + fadeIn(tween(FADE_MS)),
                    exit = shrinkVertically(tween(FADE_MS)) + fadeOut(tween(FADE_MS)),
                ) { StatusStrip(status, onStop = viewModel::stopBrowsing, onRetry = { statusAction(status) }) }
                AnimatedContent(
                    tab,
                    transitionSpec = { fadeIn(tween(FADE_MS)) togetherWith fadeOut(tween(FADE_MS)) },
                    label = "tab",
                    modifier = Modifier.weight(1f),
                ) { shown ->
                    tabStates.SaveableStateProvider(shown.name) {
                        // Every screen is opaque, so a fade is the only time one shows through another.
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            when (shown) {
                                Tab.Home -> HomeScreen(
                                    ui = ui,
                                    status = status,
                                    now = now,
                                    onOpenPhoto = { openPhone(it, MediaFilter.All) },
                                    onOpenSettings = { tabName = Tab.Settings.name },
                                    onPair = onPair,
                                    onRepair = { viewModel.forgetForRepair(onPair) },
                                    onRetry = { statusAction(status) },
                                    onResume = { viewModel.setPaused(false) },
                                    onStop = viewModel::stopBrowsing,
                                    onOpenCamera = { openCamera(it, MediaFilter.All) },
                                    onTogglePause = { viewModel.setPaused(!ui.settings.syncPaused) },
                                )
                                Tab.Camera -> CameraRoute(hasCamera = ui.camera != null, onPair = onPair, onOpenPhoto = openCamera)
                                Tab.Library -> LibraryRoute(
                                    onOpenPhoto = openPhone,
                                    hiddenPhotos = hiddenPhotos,
                                    onAllowHidden = { libraryLauncher.launch(libraryPermission()) },
                                )
                                Tab.Settings -> settings()
                            }
                        }
                    }
                }
            }
            ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Tab.entries.forEach { item ->
                    ShortNavigationBarItem(
                        selected = item == tab,
                        onClick = { tabName = item.name },
                        icon = { Icon(item.icon, null) },
                        label = { Text(item.label) },
                    )
                }
            }
        }
        var photoNeedsCamera by remember { mutableStateOf(false) }
        var viewerTools by remember { mutableStateOf(false) }
        viewerKey?.let { PhotoViewer(ViewerTarget(viewerFromCamera, it, viewerFilter), onClosed = { viewerKey = null; photoNeedsCamera = false; viewerTools = false }, onNeedsCamera = { needs -> photoNeedsCamera = needs }, onToolsShown = { viewerTools = it }) }
        // The same status as the tabs, as a capsule over the photo. It sits at the top, and makes room for the title.
        val capsuleTop by animateDpAsState(if (viewerTools) 56.dp else 8.dp, tween(FADE_MS), label = "capsuleTop")
        val overPhoto = if (viewerKey != null) viewerStatus(ui, status, photoNeedsCamera) else null
        var lastOverPhoto by remember { mutableStateOf<LinkStatus?>(null) }
        if (overPhoto != null) lastOverPhoto = overPhoto
        AnimatedVisibility(
            visible = overPhoto != null,
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(start = 12.dp, end = 12.dp, top = capsuleTop),
            enter = fadeIn(tween(FADE_MS)) + slideInVertically(tween(FADE_MS)) { -it / 2 },
            exit = fadeOut(tween(FADE_MS)) + slideOutVertically(tween(FADE_MS)) { -it / 2 },
        ) {
            lastOverPhoto?.let { shown ->
                StatusCapsule(
                    shown,
                    onStop = viewModel::stopBrowsing,
                    onRetry = { statusAction(shown) },
                )
            }
        }
        AppMessageHost(messages, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
    }
}

private const val FADE_MS = 200
