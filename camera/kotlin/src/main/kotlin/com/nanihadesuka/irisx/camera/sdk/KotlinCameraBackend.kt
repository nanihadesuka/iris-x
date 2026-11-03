package com.nanihadesuka.irisx.camera.sdk

import com.nanihadesuka.irisx.camera.api.CameraBackend
import com.nanihadesuka.irisx.camera.api.CameraSessionHandle
import com.nanihadesuka.irisx.camera.api.CardFingerprint
import com.nanihadesuka.irisx.camera.api.CatalogEntry
import com.nanihadesuka.irisx.camera.api.JoinedNetwork
import com.nanihadesuka.irisx.camera.api.PhotoRef
import com.nanihadesuka.irisx.camera.api.ReadCancelled
import com.nanihadesuka.irisx.camera.api.SessionPurpose
import kotlinx.coroutines.CancellationException
import java.io.OutputStream

/** Iris X's own SDK: the :camerasdk module, in plain Kotlin. */
class KotlinCameraBackend : CameraBackend {
    override suspend fun open(network: JoinedNetwork, phoneName: String, purpose: SessionPurpose, onAttempt: (Int) -> Unit, log: (String) -> Unit): CameraSessionHandle {
        val mode = when (purpose) {
            SessionPurpose.PhotoCatalog -> SessionMode.PhotoView
            SessionPurpose.ReservedTransfer -> SessionMode.ReservedTransfer
        }
        // CameraConnectModel.cameraOpen: up to six opens of 5 s inside a 30 s budget.
        var failure: Exception? = null
        for (attempt in 1..OPEN_ATTEMPTS) {
            onAttempt(attempt)
            try {
                return KotlinCameraSession(FujiCameraSession.open(network.socketFactory, CAMERA_HOST, phoneName, mode, log))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                log("SDK open attempt $attempt of $OPEN_ATTEMPTS failed: ${e.message}")
            }
        }
        throw failure ?: IllegalStateException("Open failed")
    }

    private companion object {
        const val OPEN_ATTEMPTS = 6
        const val CAMERA_HOST = "192.168.0.1"
    }
}

/** Puts the SDK behind the interface the app uses. */
private class KotlinCameraSession(private val sdk: FujiCameraSession) : CameraSessionHandle {
    override fun reservedCount(): Int? = sdk.reservedCount

    override suspend fun nextReserved(): PhotoRef? = sdk.nextReserved()?.let { PhotoRef(it.handle, it.name, it.sizeBytes) }

    override suspend fun catalogPage(max: Int): List<CatalogEntry> = catalogHandles().take(max).mapNotNull { catalogEntry(it) }

    override suspend fun catalogHandles(): List<Int> = catalogListing().handles

    override suspend fun catalogListing(): CardFingerprint = sdk.listCard().let { CardFingerprint(it.handles, it.countsByDate) }

    override suspend fun catalogEntry(handle: Int, withThumbnail: Boolean): CatalogEntry? {
        val info = runCatching { sdk.objectInfo(handle) }.getOrElse { if (it is CancellationException) throw it else null } ?: return null
        val thumbnail = if (withThumbnail && info.thumbnailBytes > 0) {
            runCatching { sdk.thumbnail(handle) }.getOrElse { if (it is CancellationException) throw it else null }?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return CatalogEntry(
            handle, info.name, info.sizeBytes, thumbnail, info.orientation.coerceAtLeast(1), info.capturedAt.ifEmpty { "unknown" },
            info.pixelWidth.takeIf { it > 0 } ?: -1, info.pixelHeight.takeIf { it > 0 } ?: -1,
        )
    }

    override suspend fun requestOriginals(originals: Boolean) = sdk.setQuality(if (originals) ImageQuality.Original else ImageQuality.Default)

    override suspend fun photoInfo(handle: Int): PhotoRef? = sdk.objectInfo(handle)?.let { PhotoRef(it.handle, it.name, it.sizeBytes) }

    override suspend fun download(photo: PhotoRef, sink: OutputStream) = download(photo, sink) {}

    override suspend fun download(photo: PhotoRef, sink: OutputStream, onProgress: (Long) -> Unit) {
        try {
            sdk.read(photo.handle, photo.sizeBytes, sink, onProgress = onProgress)
        } catch (e: ReadCancelledException) {
            throw ReadCancelled()
        }
    }

    override fun cancelRead() = sdk.cancelRead()

    override fun close() = sdk.close()
}
