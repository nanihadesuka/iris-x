package com.nanihadesuka.irisx.data.background

import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.DownloadMode
import com.nanihadesuka.irisx.domain.model.LocationMode
import com.nanihadesuka.irisx.domain.repository.CameraConnection
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import com.nanihadesuka.irisx.domain.usecase.DownloadNewPhotosUseCase
import com.nanihadesuka.irisx.domain.usecase.SyncLocationUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The autonomous brain, hosted by the foreground service. Runs four small loops:
 *  1. keep the BLE link alive,
 *  2. push the phone location to the camera (on request and on a battery-aware interval),
 *  3. react to "new photo" signals with a Wi-Fi download.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SyncEngine(
    private val cameras: CameraRepository,
    private val connection: CameraConnection,
    private val settings: SettingsRepository,
    private val syncLocation: SyncLocationUseCase,
    private val download: DownloadNewPhotosUseCase,
    private val notifications: SyncNotifications,
    private val log: DiagnosticsLog,
) {
    private val downloadLock = Mutex()
    @Volatile private var lastLocationAttempt = 0L

    suspend fun run() = coroutineScope {
        val camera = cameras.current() ?: return@coroutineScope

        launch { connection.run(camera) }

        // Location: every N seconds while connected, N chosen by the battery mode (Balanced = 5 min).
        launch {
            combine(connection.state, settings.settings) { s, cfg -> s to cfg }
                .distinctUntilChanged()
                .collectLatest { (state, cfg) ->
                    if (state == ConnectionState.Connected && !cfg.syncPaused && cfg.locationMode != LocationMode.Off) {
                        while (true) {
                            pushLocation()
                            delay(cfg.locationMode.intervalSeconds * 1_000L)
                        }
                    }
                }
        }
        // Location: immediate answer when the camera asks.
        launch { connection.locationRequests.collect { pushLocation() } }

        // New photo -> Wi-Fi download, debounced so a burst of shots becomes one transfer. The signal only fires
        // when the CAMERA asks for a reserved transfer (AP state 0x8003), so the camera's own setting is the gate;
        // downloadNow then follows the download mode.
        launch {
            connection.newPhotoSignals.debounce(SIGNAL_DEBOUNCE_MS).collect { downloadNow() }
        }
    }

    private suspend fun pushLocation() {
        val now = System.currentTimeMillis()
        if (now - lastLocationAttempt < MIN_LOCATION_GAP_MS) return
        lastLocationAttempt = now
        val cfg = settings.settings.first()
        if (cfg.syncPaused || cfg.locationMode == LocationMode.Off) return
        runCatching { syncLocation() }.onFailure { if (it is CancellationException) throw it else log.log("Location failed: ${it.message}") }
    }

    private suspend fun downloadNow() {
        val cfg = settings.settings.first()
        if (cfg.syncPaused) return
        if (cfg.downloadMode != DownloadMode.Automatic) {
            // Only remembered: Wi-Fi waits until the app is opened, or until the user asks.
            log.log("New photos on the camera, waiting (${cfg.downloadMode})")
            settings.update { it.copy(photosWaiting = true) }
            return
        }
        downloadLock.withLock {
            var attempt = 0
            while (attempt < MAX_TRANSFER_ATTEMPTS) {
                try {
                    val saved = download()
                    if (saved > 0) {
                        log.log("Saved $saved photo(s)")
                        notifications.photosSaved(saved)
                    }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    log.log("Download failed ($attempt): ${e.message}")
                    delay(RETRY_BACKOFF_MS * attempt)
                }
            }
        }
    }

    private companion object {
        const val SIGNAL_DEBOUNCE_MS = 250L // After AP state 0x8003
        const val CONNECT_SETTLE_MS = 2_000L
        const val EMPTY_RETRY_MS = 4_000L
        const val RETRY_BACKOFF_MS = 10_000L
        const val MAX_TRANSFER_ATTEMPTS = 2
        const val MIN_LOCATION_GAP_MS = 4_000L
    }
}
