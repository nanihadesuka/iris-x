package com.nanihadesuka.irisx.domain.model

import com.nanihadesuka.irisx.camera.api.CatalogEntry
import com.nanihadesuka.irisx.camera.api.isVideoName
import kotlinx.serialization.Serializable

/** A camera that has been paired with this phone. */
@Serializable
data class Camera(
    val bleAddress: String,
    val name: String,
    /** Wi-Fi SSID broadcast by the camera when its access point is on. */
    val wifiSsid: String? = null,
    /** Null when the camera access point is open. */
    val wifiPassphrase: String? = null,
    /** Camera access point hardware address (read over BLE), pinned when joining. */
    val wifiBssid: String? = null,
    /**
     * The number the camera issued at pairing, with 0x20000000
     * added, stored once and written back on every connect. 0 = not issued yet (a new registration).
     */
    val identificationNumber: Int = 0,
)

/** A camera seen while scanning for pairing. */
data class CameraCandidate(
    val bleAddress: String,
    val name: String,
    val signalDbm: Int,
)

sealed interface ConnectionState {
    data object NotPaired : ConnectionState

    /** Paired, but the camera is off or out of range. */
    data object CameraAway : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class NeedsAttention(val reason: Attention) : ConnectionState
}

enum class Attention { BluetoothOff, PermissionMissing, PairingLost, LocationOff }

sealed interface TransferState {
    data object Idle : TransferState

    /** A retry is waiting for the Bluetooth link to come back. */
    data object WaitingForLink : TransferState

    /** Asking the camera over Bluetooth to start its access point. */
    data object WaitingForCamera : TransferState
    data class JoiningWifi(val attempt: Int = 1) : TransferState

    /** Joined the camera Wi-Fi, opening the transfer session. */
    data class WifiConnected(val attempt: Int = 1) : TransferState
    data class Downloading(val done: Int, val total: Int, val currentName: String, val bytes: Long = 0, val size: Long = 0) : TransferState

    /** Reading names and types of photos not seen before. Each photo is read once. */
    data class ReadingCard(val done: Int, val total: Int) : TransferState

    /** The user asked to stop; the current camera step is finishing. */
    data object Stopping : TransferState

    /** A catalog session is open on the camera's Wi-Fi. */
    data object Browsing : TransferState
    data class Failed(val message: String) : TransferState
}

sealed interface PairingState {
    data object Idle : PairingState
    data object Scanning : PairingState
    data class Found(val candidates: List<CameraCandidate>) : PairingState
    data class Working(val step: PairingStep) : PairingState
    data class Done(val camera: Camera) : PairingState
    data class Failed(val message: String) : PairingState
}

enum class PairingStep { Bluetooth, Identify, Subscribe, Wifi }

/** A photo stored on the phone. */
data class SavedPhoto(
    val uri: String,
    val name: String,
    val savedAtMillis: Long,
    val sizeBytes: Long,
    /** When it was shot, from the file; 0 when unknown. */
    val takenAtMillis: Long = 0,
    /** In the folder Google Photos is meant to back up. Says nothing about whether the upload has happened. */
    val queuedForBackup: Boolean = false,
    /** Iris X already made this phone copy smaller. Compressing again would only lose quality. */
    val compressed: Boolean = false,
    /** Videos only. */
    val durationMillis: Long = 0,
) {
    val isVideo: Boolean get() = isVideoName(name)
}

/** The smaller copy of a video, beside its original. */
fun smallVideoName(name: String) = name.substringBeforeLast('.') + "_small.mp4"

enum class VideoPreset(val label: String, val shortSide: Int, val bitrate: Int) {
    Good("1080p, good quality", 1080, 8_000_000),
    Small("720p, smaller", 720, 4_000_000),
    Tiny("480p, smallest", 480, 1_500_000);

    fun estimatedBytes(durationMillis: Long): Long = bitrate / 8L * durationMillis / 1000
}

data class VideoJob(
    val name: String,
    val forBackup: Boolean,
    val percent: Int = 0,
    val running: Boolean = true,
    val error: String? = null,
)

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val timeMillis: Long,
)

data class Settings(
    val syncPaused: Boolean = false,
    val downloadMode: DownloadMode = DownloadMode.WhenOpened,
    /** The camera signalled new photos that have not been fetched yet. */
    val photosWaiting: Boolean = false,
    /** After an automatic transfer, fetch any original the camera sent only a reduced copy of. */
    val autoOriginals: Boolean = true,
    val locationMode: LocationMode = LocationMode.Balanced,
    /** The system wake-up prompt was offered once for this camera. */
    val wakePrompted: Boolean = false,
    /** Whether the first launch looked for a Pictures/Iris X left by an earlier install. */
    val libraryChecked: Boolean = false,
    /** Pictures/Iris X existed before this install, so its older photos are hidden without a permission. */
    val libraryPredatesInstall: Boolean = false,
    /** Grids fill each square cell, cropping the edges of the photo. Off shows the whole photo. */
    val squareGrid: Boolean = true,
    val photoGrouping: PhotoGrouping = PhotoGrouping.Days,
    val libraryPrompted: Boolean = false,
    val compressionMode: CompressionMode = CompressionMode.TargetSize,
    val compressionTargetKb: Int = 1024,
    val compressionPercent: Int = 50,
    val compressionMaxEdge: Int = 3840,
    val compressionQuality: Int = 80,
    val compressOnDownload: Boolean = true,
    val compressOnBackup: Boolean = true,
)

/** When new photos are fetched over Wi-Fi. See docs/design/UI_UX_DESIGN.md. */
enum class DownloadMode { WhenOpened, Automatic, OnlyWhenAsked }

enum class CompressionMode { TargetSize, Percent, Manual }

/** A rough guess for showing savings ahead of time; the real size depends on the photo. */
fun Settings.estimatedCompressedSize(size: Long): Long = when (compressionMode) {
    CompressionMode.TargetSize -> minOf(size, compressionTargetKb * 1024L)
    CompressionMode.Percent -> size * (100 - compressionPercent) / 100
    CompressionMode.Manual -> {
        val pixels = (compressionMaxEdge.toFloat() / FULL_SIZE_EDGE).coerceAtMost(1f).let { it * it }
        val quality = (compressionQuality / 95f).let { it * it * it }
        minOf(size, (size * pixels * quality).toLong())
    }
}

private const val FULL_SIZE_EDGE = 7728

/** What compressing would do, with nothing saved. [compressed] is null when the photo would stay as it is. */
class CompressionPreview(val original: ByteArray, val compressed: ByteArray?)

sealed interface CompressOutcome {
    data class Compressed(val savedBytes: Long) : CompressOutcome
    /** Already at or below what the settings ask for. */
    data object Unchanged : CompressOutcome
    data object Failed : CompressOutcome
}

data class CompressionProgress(
    val done: Int = 0,
    val total: Int = 0,
    val savedBytes: Long = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    val running: Boolean get() = done < total
}

enum class LocationMode(val intervalSeconds: Int) {
    Off(0),            // never sent, even when the camera asks
    BatterySaver(900), // 15 min
    Balanced(300),     //  5 min
    Precise(60),       //  1 min
}

/** Originals are 7728 px on the long side (OBSERVED). A smaller crop only costs a check that downloads nothing. */
const val FULL_SIZE_MIN_EDGE = 7000

enum class CatalogPhase { Idle, Connecting, Reading, Live, Failed }

/** What the camera browser is doing, and everything it has read so far. */
data class CatalogState(
    val phase: CatalogPhase = CatalogPhase.Idle,
    val entries: List<CatalogEntry> = emptyList(),
    /** Every photo on the card, newest first, known before its thumbnail is read. */
    val handles: List<Int> = emptyList(),
    /** Photos on the card, once the handle list has been read. */
    val total: Int? = null,
    val download: CatalogDownload? = null,
    val viewing: CatalogView? = null,
    /** New photos on their way: null = none, 0 = some but not yet how many, otherwise how many are left. */
    val incoming: Int? = null,
    val message: String? = null,
)

/** [PreviewReady] is the end of opening a photo: the original is only fetched on zoom or Save. */
enum class ViewStage { Waiting, Preview, PreviewReady, Hd, Failed }

/** A photo opened from the camera: its preview, sharp at screen size, kept in a small disk cache. */
class CatalogView(val handle: Int, val stage: ViewStage, val preview: ByteArray? = null)

data class CatalogDownload(
    val done: Int,
    val total: Int,
    val currentName: String,
    val currentBytes: Long = 0,
    val currentSize: Long = 0,
) {
    /** Share of the current file received, or null until the camera has reported any. */
    val currentFraction: Float? get() = if (currentBytes > 0 && currentSize > 0) (currentBytes.toFloat() / currentSize).coerceAtMost(1f) else null
}

