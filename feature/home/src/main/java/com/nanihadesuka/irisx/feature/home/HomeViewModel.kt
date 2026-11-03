package com.nanihadesuka.irisx.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.camera.api.CatalogEntry
import com.nanihadesuka.irisx.domain.model.CatalogState
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.Settings
import com.nanihadesuka.irisx.domain.model.CatalogPhase
import com.nanihadesuka.irisx.domain.model.TransferState
import com.nanihadesuka.irisx.domain.repository.CameraConnection
import com.nanihadesuka.irisx.domain.repository.CameraPresence
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.PhotoStore
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import com.nanihadesuka.irisx.domain.repository.SyncActivity
import com.nanihadesuka.irisx.domain.repository.SyncController
import com.nanihadesuka.irisx.domain.usecase.CameraBrowser
import com.nanihadesuka.irisx.domain.usecase.ForgetCameraUseCase
import com.nanihadesuka.irisx.domain.usecase.PhotoCompressor
import com.nanihadesuka.irisx.domain.usecase.VideoCompressor
import com.nanihadesuka.irisx.domain.model.VideoPreset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.nanihadesuka.irisx.domain.model.DownloadMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val camera: Camera? = null,
    val connection: ConnectionState = ConnectionState.NotPaired,
    val transfer: TransferState = TransferState.Idle,
    val lastLocationMillis: Long? = null,
    val lastConnectedMillis: Long? = null,
    val photos: List<SavedPhoto> = emptyList(),
    val settings: Settings = Settings(),
    val loaded: Boolean = false,
    /** Photos the catalog knows about that are not on this phone. */
    val cameraMissing: Int = 0,
    /** Photos on the card as of the last read; null before the first. */
    val cameraTotal: Int? = null,
    /** When [transfer] last changed. */
    val transferSince: Long = 0,
    /** A browse or a download the user asked for is running, so they can stop it. */
    val browserActive: Boolean = false,
    /** The camera's newest photo, when the phone does not have it yet. */
    val latestOnCamera: CatalogEntry? = null,
    /** Photos on their way from the camera right now; null when none are. */
    val incoming: Int? = null,
    val bluetoothOff: Boolean = false,
    val wifiOff: Boolean = false,
)

private fun missingOnPhone(catalog: CatalogState, photos: List<SavedPhoto>): List<CatalogEntry> {
    val names = photos.mapTo(HashSet()) { it.name.lowercase() }
    return catalog.entries.filter { it.isJpeg && it.name.lowercase() !in names }
}

private const val OPEN_SETTLE_MS = 3_000L

class HomeViewModel(
    cameras: CameraRepository,
    private val connection: CameraConnection,
    private val activity: SyncActivity,
    private val settings: SettingsRepository,
    private val controller: SyncController,
    private val forgetCamera: ForgetCameraUseCase,
    private val presence: CameraPresence,
    private val store: PhotoStore,
    private val browser: CameraBrowser,
    private val downloadNew: com.nanihadesuka.irisx.domain.usecase.DownloadNewPhotosUseCase,
) : ViewModel() {
    private var waitingJob: kotlinx.coroutines.Job? = null

    /**
     * "When I open the app": while the app is in front, photos the camera signalled are fetched. The short wait means a
     * quick glance starts no Wi-Fi. Leaving before that cancels it; once fetching has started it finishes.
     */
    fun onForeground() {
        if (waitingJob?.isActive == true) return
        waitingJob = viewModelScope.launch {
            settings.settings
                .map { it.photosWaiting && it.downloadMode == DownloadMode.WhenOpened && !it.syncPaused }
                .distinctUntilChanged()
                .collectLatest { waiting ->
                    if (!waiting) return@collectLatest
                    kotlinx.coroutines.delay(OPEN_SETTLE_MS)
                    connection.state.first { it == ConnectionState.Connected }
                    if (connection.offeringTransfer) {
                        // The camera has its Wi-Fi up for this right now: take it the way it is offered.
                        browser.close()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            downloadNew()
                            if (activity.transfer.value !is TransferState.Failed) settings.update { it.copy(photosWaiting = false) }
                        }
                    } else {
                        browser.fetchNew()
                    }
                }
        }
    }

    fun onBackground() {
        waitingJob?.cancel()
    }

    init {
        // Once per install, before this install writes anything into the folder.
        viewModelScope.launch {
            if (!settings.settings.first().libraryChecked) {
                val old = store.folderPredatesInstall()
                settings.update { it.copy(libraryChecked = true, libraryPredatesInstall = old) }
            }
        }
    }

    fun libraryPrompted() {
        viewModelScope.launch { settings.update { it.copy(libraryPrompted = true) } }
    }

    fun refreshLibrary() = store.refresh()

    /** Offered once, while connected. Returns the system dialog to show, if any. */
    suspend fun wakeRequest(): android.content.IntentSender? {
        val camera = state.value.camera ?: return null
        settings.update { it.copy(wakePrompted = true) }
        if (presence.isAssociated(camera.bleAddress)) {
            presence.observe(camera.bleAddress)
            return null
        }
        return presence.requestAssociation(camera.bleAddress)
    }

    val state: StateFlow<HomeUiState> = combine(
        cameras.pairedCamera,
        connection.state,
        activity.transfer,
        connection.lastLocationSentMillis,
        store.recent,
    ) { camera, conn, transfer, location, photos ->
        HomeUiState(
            camera = camera,
            connection = if (camera == null) ConnectionState.NotPaired else conn,
            transfer = transfer,
            lastLocationMillis = location,
            photos = photos,
            loaded = true,
        )
    }.combine(settings.settings) { ui, cfg -> ui.copy(settings = cfg) }
        .combine(connection.lastConnectedMillis) { ui, last -> ui.copy(lastConnectedMillis = last) }
        .combine(browser.state) { ui, catalog ->
            ui.copy(
                cameraMissing = missingOnPhone(catalog, ui.photos).size,
                cameraTotal = catalog.total,
                latestOnCamera = catalog.entries.maxByOrNull { it.handle }?.takeIf { newest -> newest.isJpeg && ui.photos.none { it.name.equals(newest.name, ignoreCase = true) } },
                incoming = catalog.incoming,
                transferSince = activity.changedAtMillis,
                browserActive = catalog.phase in setOf(CatalogPhase.Connecting, CatalogPhase.Reading, CatalogPhase.Live) || catalog.download != null,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Called whenever the app comes to the front: the service is running and searching at full speed. */
    fun onAppVisible() {
        val s = state.value
        if (s.camera != null && !s.settings.syncPaused) {
            controller.start()
            connection.reconnectNow()
        }
    }

    fun getMissing() {
        val s = state.value
        browser.download(missingOnPhone(browser.state.value, s.photos))
    }

    fun stopBrowsing() = browser.stop()

    /** Gets the link back and browses, whatever state things are in. */
    fun connectCamera() = browser.recover()

    /** After a failure this repeats what failed, wherever it is pressed. Otherwise it only hurries the link. */
    fun retry() {
        if (browser.state.value.phase == CatalogPhase.Failed || state.value.transfer is TransferState.Failed) {
            browser.recover()
        } else {
            controller.start()
            connection.reconnectNow()
        }
    }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch {
            settings.update { it.copy(syncPaused = paused) }
            if (paused) {
                browser.stop()
                controller.stop()
            } else {
                retry()
            }
        }
    }

    /** The camera dropped the registration: clear ours so pairing starts clean. */
    fun forgetForRepair(then: () -> Unit) {
        viewModelScope.launch {
            forgetCamera()
            then()
        }
    }
}

data class CameraUiState(
    val catalog: CatalogState = CatalogState(),
    /** Lower-case file name to the phone copy's uri. */
    val onPhone: Map<String, String> = emptyMap(),
    val connection: ConnectionState = ConnectionState.NotPaired,
    val transfer: TransferState = TransferState.Idle,
    val squareGrid: Boolean = true,
    val grouping: com.nanihadesuka.irisx.domain.model.PhotoGrouping = com.nanihadesuka.irisx.domain.model.PhotoGrouping.Days,
) {
    fun isOnPhone(entry: CatalogEntry) = entry.name.lowercase() in onPhone
    val missing: List<CatalogEntry> get() = catalog.entries.filter { it.isJpeg && !isOnPhone(it) }
    /** Missing and newer than the newest photo already on the phone. With nothing on the phone, nothing counts as new. */
    val newPhotos: List<CatalogEntry> get() {
        val newestHere = catalog.entries.filter(::isOnPhone).maxOfOrNull { it.handle } ?: return emptyList()
        return missing.filter { it.handle > newestHere }
    }
}

class CameraViewModel(
    private val browser: CameraBrowser,
    store: PhotoStore,
    connection: CameraConnection,
    activity: SyncActivity,
    settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<CameraUiState> = combine(browser.state, store.recent, connection.state, activity.transfer) { catalog, photos, conn, transfer ->
        CameraUiState(catalog, photos.associate { it.name.lowercase() to it.uri }, conn, transfer)
    }.combine(settings.settings) { ui, cfg -> ui.copy(squareGrid = cfg.squareGrid, grouping = cfg.photoGrouping) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraUiState())

    /** Every Browse, Refresh and Retry: brings the Bluetooth link back first if it is down. */
    fun browse() = browser.recover()
    fun view(entry: CatalogEntry, network: Boolean = true) = browser.view(entry, network)
    fun keepPreviews(names: List<String>) = browser.keepPreviews(names)
    fun stopViewing() = browser.stopViewing()
    fun viewingOnly(handle: Int?) = browser.viewingOnly(handle)
    fun prioritize(handles: List<Int>) = browser.prioritize(handles)
    fun download(entries: List<CatalogEntry>) = browser.download(entries)
}

class LibraryViewModel(
    private val store: PhotoStore,
    private val compressor: PhotoCompressor,
    private val videoCompressor: VideoCompressor,
    settingsRepository: SettingsRepository,
    private val browser: CameraBrowser,
) : ViewModel() {
    fun keepPreviews(names: List<String>) = browser.keepPreviews(names)
    val settings = settingsRepository.settings
    val compression = compressor.state
    val finishing = compressor.finishing
    val videoJob = videoCompressor.state
    fun compressVideo(video: SavedPhoto, preset: VideoPreset, thenBackup: Boolean) = videoCompressor.start(video, preset, thenBackup)
    fun cancelVideoJob() = videoCompressor.cancel()
    fun clearVideoJob() = videoCompressor.clear()
    suspend fun previewCompression(photo: SavedPhoto, custom: Settings) = store.previewCompression(photo.uri, custom)
    fun compress(photos: List<SavedPhoto>, custom: Map<String, Settings> = emptyMap()) =
        compressor.enqueue(photos.filter { !it.compressed && !it.isVideo }.map { it.uri }, custom)
    suspend fun moveToBackup(uri: String) {
        compressor.beforeBackup(uri)
        store.moveToBackup(uri)
    }

    val photos: StateFlow<List<SavedPhoto>?> = store.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
