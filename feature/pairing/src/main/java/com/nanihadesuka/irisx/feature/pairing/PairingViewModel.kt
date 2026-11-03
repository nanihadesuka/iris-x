package com.nanihadesuka.irisx.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.model.CameraCandidate
import com.nanihadesuka.irisx.domain.model.PairingState
import com.nanihadesuka.irisx.domain.model.PairingStep
import com.nanihadesuka.irisx.domain.repository.CameraPairing
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import com.nanihadesuka.irisx.domain.repository.SyncController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PairingStage { Permissions, Searching, Pairing, Done, Failed }

data class PairingUiState(
    val stage: PairingStage = PairingStage.Permissions,
    val candidates: List<CameraCandidate> = emptyList(),
    val step: PairingStep? = null,
    val error: String? = null,
    val camera: Camera? = null,
)

class PairingViewModel(
    private val pairing: CameraPairing,
    private val controller: SyncController,
    private val settings: SettingsRepository,
    private val cameras: CameraRepository,
) : ViewModel() {

    private val permissionsReady = MutableStateFlow(false)
    private var scanJob: Job? = null
    private var pairJob: Job? = null
    private var autoPairJob: Job? = null

    val state: StateFlow<PairingUiState> = combine(permissionsReady, pairing.state) { ready, p ->
        when {
            !ready -> PairingUiState(PairingStage.Permissions)
            p is PairingState.Idle || p is PairingState.Scanning -> PairingUiState(PairingStage.Searching)
            p is PairingState.Found -> PairingUiState(PairingStage.Searching, candidates = p.candidates)
            p is PairingState.Working -> PairingUiState(PairingStage.Pairing, step = p.step)
            p is PairingState.Done -> PairingUiState(PairingStage.Done, camera = p.camera)
            p is PairingState.Failed -> PairingUiState(PairingStage.Failed, error = p.message)
            else -> PairingUiState()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PairingUiState())

    fun onPermissionsReady() {
        if (permissionsReady.value) return
        permissionsReady.value = true
        startScan()
    }

    /** Opened only to fix permissions: a camera is already paired, so there is nothing to scan for. */
    suspend fun alreadyPaired(): Boolean = (cameras.current() != null).also { if (it) controller.start() }

    fun onPermissionsLost() {
        permissionsReady.value = false
        scanJob?.cancel()
    }

    fun startScan() {
        scanJob?.cancel()
        pairing.reset()
        scanJob = viewModelScope.launch { pairing.scan() }
        // Fewer taps: when exactly one camera shows up, pair with it automatically.
        autoPairJob?.cancel()
        autoPairJob = viewModelScope.launch {
            pairing.state.collect { s ->
                if (s is PairingState.Found && s.candidates.size == 1) {
                    delay(AUTO_PAIR_DELAY_MS)
                    val latest = pairing.state.value
                    if (latest is PairingState.Found && latest.candidates.size == 1) pair(latest.candidates.first())
                }
            }
        }
    }

    fun pair(candidate: CameraCandidate) {
        if (pairJob?.isActive == true) return
        autoPairJob?.cancel()
        scanJob?.cancel()
        pairJob = viewModelScope.launch {
            pairing.pair(candidate)
            if (pairing.state.value is PairingState.Done) {
                settings.update { it.copy(syncPaused = false) }
                controller.start()
            }
        }
    }

    fun retry() = startScan()

    override fun onCleared() {
        pairing.stopScan()
    }

    private companion object {
        const val AUTO_PAIR_DELAY_MS = 1_500L
    }
}
