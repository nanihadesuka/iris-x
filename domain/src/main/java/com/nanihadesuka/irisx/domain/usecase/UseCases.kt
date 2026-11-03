package com.nanihadesuka.irisx.domain.usecase

import com.nanihadesuka.irisx.camera.api.SessionPurpose
import com.nanihadesuka.irisx.domain.model.FULL_SIZE_MIN_EDGE
import com.nanihadesuka.irisx.domain.model.TransferState
import com.nanihadesuka.irisx.domain.repository.CameraConnection
import com.nanihadesuka.irisx.domain.repository.CameraPairing
import com.nanihadesuka.irisx.domain.repository.CameraPresence
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.DownloadLedger
import com.nanihadesuka.irisx.domain.repository.LocationProvider
import com.nanihadesuka.irisx.domain.repository.PhotoStore
import com.nanihadesuka.irisx.domain.repository.PhotoTransferGateway
import com.nanihadesuka.irisx.domain.repository.SyncActivity
import com.nanihadesuka.irisx.domain.repository.SyncController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.OutputStream

/** Fetches every JPEG on the camera that is not on the phone yet. Returns how many were saved. */
class DownloadNewPhotosUseCase(
    private val cameras: CameraRepository,
    private val gateway: PhotoTransferGateway,
    private val store: PhotoStore,
    private val ledger: DownloadLedger,
    private val activity: SyncActivity,
    private val browser: CameraBrowser,
    private val settings: SettingsRepository,
    private val compressor: PhotoCompressor,
) {
    private companion object {
        const val MAX_PER_SESSION = 200
    }

    suspend operator fun invoke(): Int {
        val camera = cameras.current() ?: return 0
        browser.incomingStarted() // ends any browse: the camera only offers this transfer for a while
        activity.set(TransferState.JoiningWifi())
        val names = mutableListOf<String>()
        return try {
            val saved = gateway.withSession(camera, SessionPurpose.ReservedTransfer) { session ->
                // ReadImageInfo(1) -> ReadImage(1) -> save -> repeat while the
                // camera still has reserved images. The camera advances its queue, so the handle is always 1.
                var count = 0
                var received = 0
                var previous: String? = null
                while (received < MAX_PER_SESSION) {
                    val queued = session.reservedCount()
                    queued?.let { browser.incomingCount(it - received) }
                    val photo = session.nextReserved() ?: break
                    if (photo.ledgerKey == previous) break // camera did not advance its queue: stop instead of looping
                    previous = photo.ledgerKey
                    activity.set(TransferState.Downloading(received, queued?.coerceAtLeast(received + 1) ?: (received + 1), photo.name))
                    if (store.existingSize(photo.name) == null) {
                        val total = queued?.coerceAtLeast(received + 1) ?: (received + 1)
                        store.save(photo.name) { out ->
                            session.download(photo, out) { bytes -> activity.set(TransferState.Downloading(received, total, photo.name, bytes, photo.sizeBytes)) }
                        }
                        ledger.markDownloaded(photo.ledgerKey)
                        count++
                    } else {
                        // Already here, but it must still be read or the camera's queue would not advance.
                        session.download(photo, object : OutputStream() { override fun write(b: Int) = Unit; override fun write(b: ByteArray, off: Int, len: Int) = Unit })
                    }
                    names += photo.name
                    received++
                }
                count
            }
            activity.set(TransferState.Idle)
            // With originals on, only a reduced copy needs another Wi-Fi session: the upgrade compresses what it ends up with.
            val autoOriginals = settings.settings.first().autoOriginals
            val reduced = if (autoOriginals) names.filter { (store.longestEdge(it) ?: 0) < FULL_SIZE_MIN_EDGE } else emptyList()
            (names - reduced.toSet()).forEach(compressor::afterDownload)
            browser.incomingFinished(reduced)
            saved
        } catch (e: CancellationException) {
            browser.incomingCleared()
            activity.set(TransferState.Idle)
            throw e
        } catch (e: Exception) {
            browser.incomingCleared()
            activity.set(TransferState.Failed(e.message ?: "Transfer failed"))
            throw e
        }
    }
}

/** Reads the phone location and pushes it to the camera. */
class SyncLocationUseCase(
    private val location: LocationProvider,
    private val connection: CameraConnection,
) {
    suspend operator fun invoke(): Boolean {
        val fix = location.currentFix() ?: return false
        connection.sendLocation(fix)
        return true
    }
}

class ForgetCameraUseCase(
    private val cameras: CameraRepository,
    private val controller: SyncController,
    private val pairing: CameraPairing,
    private val browser: CameraBrowser,
    private val presence: CameraPresence,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke() {
        controller.stop()
        cameras.current()?.let {
            browser.forget(it.bleAddress)
            presence.forget(it.bleAddress)
            pairing.removeBond(it.bleAddress)
        }
        settings.update { it.copy(wakePrompted = false) }
        cameras.forget()
    }
}
