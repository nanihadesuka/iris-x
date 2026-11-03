package com.nanihadesuka.irisx.domain.repository

import com.nanihadesuka.irisx.camera.api.PhotoSession
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.model.CameraCandidate
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.LocationFix
import com.nanihadesuka.irisx.domain.model.PairingState
import com.nanihadesuka.irisx.camera.api.PhotoRef
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.Settings
import com.nanihadesuka.irisx.domain.model.TransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.OutputStream

/** Persists which camera is paired. */
interface CameraRepository {
    val pairedCamera: Flow<Camera?>
    suspend fun current(): Camera?
    suspend fun save(camera: Camera)
    suspend fun forget()
}

/** Low-power, always-on link with the paired camera. */
interface CameraConnection {
    /** The camera has its Wi-Fi up right now for a reserved transfer. */
    val offeringTransfer: Boolean get() = false

    val state: StateFlow<ConnectionState>

    /** Emits whenever the camera signals that something new was shot. */
    val newPhotoSignals: Flow<Unit>

    /** Emits whenever the camera asks the phone for a location fix. */
    val locationRequests: Flow<Unit>
    val lastLocationSentMillis: StateFlow<Long?>

    /** When the Bluetooth link was last up. */
    val lastConnectedMillis: StateFlow<Long?>

    /** Emits when the camera reports that its access point is up (AP state launched) after [requestAccessPoint]. */
    val accessPointLaunched: Flow<Unit>

    /** Keeps the connection alive (reconnecting as needed) until cancelled or [disconnect]. */
    suspend fun run(camera: Camera)
    fun disconnect()

    /** Cuts any wait short and searches at full speed: the user is here now. */
    fun reconnectNow()

    /** Asks the camera to start its Wi-Fi access point so waiting photos can be fetched now. */
    suspend fun requestAccessPoint()
    suspend fun sendLocation(fix: LocationFix)
}

/** One-time pairing between app and camera (BLE bond + remember Wi-Fi). */
interface CameraPairing {
    val state: StateFlow<PairingState>
    suspend fun scan()
    fun stopScan()
    suspend fun pair(candidate: CameraCandidate)
    fun reset()

    /** Removes the phone's Bluetooth bond with the camera. */
    fun removeBond(address: String)
}

interface LocationProvider {
    suspend fun currentFix(): LocationFix?
}

/** Joins the camera's 5 GHz network, opens a session, and tears it all down again. */
interface PhotoTransferGateway {
    /** False when the phone's Wi-Fi is switched off, so a session cannot work. */
    val wifiEnabled: Boolean get() = true

    suspend fun <T> withSession(camera: Camera, purpose: com.nanihadesuka.irisx.camera.api.SessionPurpose, block: suspend (PhotoSession) -> T): T
}

interface PhotoStore {
    val recent: Flow<List<SavedPhoto>>
    suspend fun save(name: String, write: suspend (OutputStream) -> Unit): SavedPhoto

    /** Size of the phone copy called [name], if there is one. */
    suspend fun existingSize(name: String): Long?

    /** A JPEG of the phone copy of [name], no longer than [longEdge], with pixels as stored (no EXIF); null if there is none. */
    suspend fun smallCopy(name: String, longEdge: Int): ByteArray? = null

    /** The longest side in pixels of the phone copy of [name], or null when unknown. */
    suspend fun longestEdge(name: String): Int? = null

    /** Saves [name], and only once it is complete removes the copy it replaces. */
    suspend fun saveReplacing(name: String, write: suspend (OutputStream) -> Unit): SavedPhoto

    /** Rewrites the phone copy smaller, keeping its EXIF. It stays where it is, with the same uri. */
    suspend fun compress(uri: String, settings: com.nanihadesuka.irisx.domain.model.Settings): com.nanihadesuka.irisx.domain.model.CompressOutcome

    /** Writes a smaller copy beside the video and returns its uri. The original is not touched. */
    suspend fun compressVideo(uri: String, name: String, preset: com.nanihadesuka.irisx.domain.model.VideoPreset, onProgress: (Int) -> Unit): String

    /** The phone copy called [name], unless it is missing or already compressed. */
    suspend fun uncompressedUri(name: String): String?

    /** The same compression, without saving anything. */
    suspend fun previewCompression(uri: String, settings: com.nanihadesuka.irisx.domain.model.Settings): com.nanihadesuka.irisx.domain.model.CompressionPreview

    /** Moves the phone copy into the backup folder. Throws SecurityException for a file saved by an earlier install. */
    suspend fun moveToBackup(uri: String)

    /** Pictures/Iris X is older than this install: photos saved by the previous one are invisible until read access is granted. */
    fun folderPredatesInstall(): Boolean

    /** Lists again, after a permission change the system does not announce. */
    fun refresh()
}

/** Camera thumbnails kept between sessions, so only photos not seen before are read again. */
interface CatalogCache {
    suspend fun load(address: String): List<com.nanihadesuka.irisx.camera.api.CatalogEntry>
    suspend fun put(address: String, entry: com.nanihadesuka.irisx.camera.api.CatalogEntry)

    /** Drops everything except [handles]. */
    suspend fun retain(address: String, handles: Set<Int>)
    suspend fun clear(address: String)

    /** What the card looked like at the last browse, or null if unknown. */
    /** A screen-sized preview kept from an earlier visit, so reopening a photo needs no Wi-Fi. */
    suspend fun loadPreview(address: String, name: String): ByteArray? = null

    /** Stores [bytes] for later, re-encoded smaller on the phone when that saves space. */
    suspend fun savePreview(address: String, name: String, bytes: ByteArray) = Unit

    suspend fun loadFingerprint(address: String): com.nanihadesuka.irisx.camera.api.CardFingerprint? = null
    suspend fun saveFingerprint(address: String, fingerprint: com.nanihadesuka.irisx.camera.api.CardFingerprint) = Unit
}

/** Remembers photos already saved so nothing is downloaded twice. */
interface DownloadLedger {
    suspend fun isDownloaded(key: String): Boolean
    suspend fun markDownloaded(key: String)
}

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}

/** Shared live view of what the sync engine is doing. */
interface SyncActivity {
    val transfer: StateFlow<TransferState>

    /** When [transfer] last changed: no change for a long time means a step may be stuck. */
    val changedAtMillis: Long
    fun set(state: TransferState)
}

/** The system watches for the camera and wakes the app when it powers on, without the app scanning. */
interface CameraPresence {
    /** Bumps when the association changes. */
    val changes: StateFlow<Int>
    fun isAssociated(address: String): Boolean

    /** The system dialog to confirm, or null when there is nothing to confirm or it failed. */
    suspend fun requestAssociation(address: String): android.content.IntentSender?
    fun observe(address: String)
    fun forget(address: String)
}

/** Starts / stops the background engine. */
interface SyncController {
    fun start()
    fun stop()
}

/** Rolling in-app log used by the diagnostics screen (BLE events, transfers, errors). */
interface DiagnosticsLog {
    val entries: StateFlow<List<String>>
    fun log(message: String)
}
