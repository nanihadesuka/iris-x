package com.nanihadesuka.irisx.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.model.CompressionMode
import com.nanihadesuka.irisx.domain.model.LocationMode
import com.nanihadesuka.irisx.domain.model.Settings
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.DownloadLedger
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal val Context.irisxStore: DataStore<Preferences> by preferencesDataStore("irisx")

class DataStoreCameraRepository(context: Context) : CameraRepository {
    private val store = context.applicationContext.irisxStore
    private val key = stringPreferencesKey("camera")

    override val pairedCamera: Flow<Camera?> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { Json.decodeFromString(Camera.serializer(), it) }.getOrNull() }
    }

    override suspend fun current(): Camera? = pairedCamera.first()

    override suspend fun save(camera: Camera) {
        store.edit { it[key] = Json.encodeToString(Camera.serializer(), camera) }
    }

    override suspend fun forget() {
        store.edit { it.remove(key) }
    }
}

class DataStoreSettingsRepository(context: Context) : SettingsRepository {
    private val store = context.applicationContext.irisxStore
    private val paused = booleanPreferencesKey("paused")
    private val auto = booleanPreferencesKey("auto_download")
    private val downloadMode = stringPreferencesKey("download_mode")
    private val photosWaiting = booleanPreferencesKey("photos_waiting")
    private val mode = stringPreferencesKey("location_mode")
    private val autoOriginals = booleanPreferencesKey("auto_originals")
    private val wakePrompted = booleanPreferencesKey("wake_prompted")
    private val libraryChecked = booleanPreferencesKey("library_checked")
    private val libraryOld = booleanPreferencesKey("library_predates_install")
    private val libraryPrompted = booleanPreferencesKey("library_prompted")
    private val compressMode = stringPreferencesKey("compress_mode")
    private val compressTarget = intPreferencesKey("compress_target_kb")
    private val compressPercent = intPreferencesKey("compress_percent")
    private val compressEdge = intPreferencesKey("compress_max_edge")
    private val compressQuality = intPreferencesKey("compress_quality")
    private val compressOnDownload = booleanPreferencesKey("compress_on_download")
    private val compressOnBackup = booleanPreferencesKey("compress_on_backup")
    private val squareGrid = booleanPreferencesKey("square_grid")
    private val photoGrouping = stringPreferencesKey("photo_grouping")

    /**
     * The interval behind each [LocationMode] was rescaled (Precise used to be 10 s, now 60 s), so a value stored
     * under the old scale would mean something different. This one-shot flag resets it to the new default once.
     */
    private val rescaled = booleanPreferencesKey("location_mode_rescaled")

    private fun Preferences.toSettings() = Settings(
        syncPaused = this[paused] ?: false,
        // The old switch being off meant "never by itself".
        downloadMode = this[downloadMode]?.let { runCatching { com.nanihadesuka.irisx.domain.model.DownloadMode.valueOf(it) }.getOrNull() }
            ?: if (this[auto] == false) com.nanihadesuka.irisx.domain.model.DownloadMode.OnlyWhenAsked else com.nanihadesuka.irisx.domain.model.DownloadMode.WhenOpened,
        photosWaiting = this[photosWaiting] ?: false,
        autoOriginals = this[autoOriginals] ?: true,
        compressionMode = this[compressMode]?.let { runCatching { CompressionMode.valueOf(it) }.getOrNull() } ?: CompressionMode.TargetSize,
        compressionTargetKb = this[compressTarget] ?: 1024,
        compressionPercent = this[compressPercent] ?: 50,
        compressionMaxEdge = this[compressEdge] ?: 3840,
        compressionQuality = this[compressQuality] ?: 80,
        compressOnDownload = this[compressOnDownload] ?: true,
        compressOnBackup = this[compressOnBackup] ?: true,
        locationMode = this[mode]
            ?.takeIf { this[rescaled] == true }
            ?.let { runCatching { LocationMode.valueOf(it) }.getOrNull() }
            ?: LocationMode.Balanced,
        wakePrompted = this[wakePrompted] ?: false,
        squareGrid = this[squareGrid] ?: true,
        photoGrouping = this[photoGrouping]?.let { runCatching { com.nanihadesuka.irisx.domain.model.PhotoGrouping.valueOf(it) }.getOrNull() } ?: com.nanihadesuka.irisx.domain.model.PhotoGrouping.Days,
        libraryChecked = this[libraryChecked] ?: false,
        libraryPredatesInstall = this[libraryOld] ?: false,
        libraryPrompted = this[libraryPrompted] ?: false,
    )

    override val settings: Flow<Settings> = store.data.map { it.toSettings() }

    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit { p ->
            val next = transform(p.toSettings())
            p[paused] = next.syncPaused
            p[downloadMode] = next.downloadMode.name
            p[photosWaiting] = next.photosWaiting
            p[autoOriginals] = next.autoOriginals
            p[compressMode] = next.compressionMode.name
            p[compressTarget] = next.compressionTargetKb
            p[compressPercent] = next.compressionPercent
            p[compressEdge] = next.compressionMaxEdge
            p[compressQuality] = next.compressionQuality
            p[compressOnDownload] = next.compressOnDownload
            p[compressOnBackup] = next.compressOnBackup
            p[mode] = next.locationMode.name
            p[rescaled] = true
            p[wakePrompted] = next.wakePrompted
            p[squareGrid] = next.squareGrid
            p[photoGrouping] = next.photoGrouping.name
            p[libraryChecked] = next.libraryChecked
            p[libraryOld] = next.libraryPredatesInstall
            p[libraryPrompted] = next.libraryPrompted
        }
    }
}

class DataStoreDownloadLedger(context: Context) : DownloadLedger {
    private val store = context.applicationContext.irisxStore
    private val setKey = stringSetPreferencesKey("downloaded")

    override suspend fun isDownloaded(key: String): Boolean =
        key in (store.data.first()[setKey] ?: emptySet())

    override suspend fun markDownloaded(key: String) {
        store.edit { it[setKey] = (it[setKey] ?: emptySet()) + key }
    }
}
