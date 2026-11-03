package com.nanihadesuka.irisx.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.model.LocationMode
import com.nanihadesuka.irisx.domain.model.Settings
import com.nanihadesuka.irisx.domain.repository.CameraPresence
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import com.nanihadesuka.irisx.domain.repository.PhotoStore
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import com.nanihadesuka.irisx.domain.repository.SyncController
import com.nanihadesuka.irisx.domain.usecase.ForgetCameraUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: Settings = Settings(),
    val camera: Camera? = null,
    val log: List<String> = emptyList(),
    val wakeActive: Boolean = false,
    /** Sizes of the photos on the phone, for showing what compression would save. */
    val photoSizes: List<Long> = emptyList(),
)

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val cameras: CameraRepository,
    private val forgetCamera: ForgetCameraUseCase,
    private val controller: SyncController,
    private val presence: CameraPresence,
    store: PhotoStore,
    diagnostics: DiagnosticsLog,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepo.settings, cameras.pairedCamera, diagnostics.entries,
        presence.changes, store.recent,
    ) { s, c, l, _, photos ->
        SettingsUiState(s, c, l, c != null && presence.isAssociated(c.bleAddress), photos.filter { !it.compressed && !it.isVideo }.map { it.sizeBytes })
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setPaused(paused: Boolean) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(syncPaused = paused) }
            if (paused) controller.stop() else controller.start()
        }
    }

    fun setDownloadMode(mode: com.nanihadesuka.irisx.domain.model.DownloadMode) = update { it.copy(downloadMode = mode) }
    fun setAutoOriginals(on: Boolean) = update { it.copy(autoOriginals = on) }
    fun setSquareGrid(on: Boolean) = update { it.copy(squareGrid = on) }
    fun setPhotoGrouping(grouping: com.nanihadesuka.irisx.domain.model.PhotoGrouping) = update { it.copy(photoGrouping = grouping) }
    fun updateCompression(transform: (Settings) -> Settings) = update(transform)
    fun setLocationMode(mode: LocationMode) = update { it.copy(locationMode = mode) }

    suspend fun wakeRequest(): android.content.IntentSender? =
        state.value.camera?.let { presence.requestAssociation(it.bleAddress) }

    fun forget() {
        viewModelScope.launch { forgetCamera() }
    }

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }
}
