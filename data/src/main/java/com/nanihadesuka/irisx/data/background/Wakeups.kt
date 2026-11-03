package com.nanihadesuka.irisx.data.background

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nanihadesuka.irisx.domain.repository.CameraConnection
import com.nanihadesuka.irisx.domain.repository.CameraPresence
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import com.nanihadesuka.irisx.domain.repository.SyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Starts the sync service when the phone boots or the app is updated. */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val cameras: CameraRepository by inject()
    private val settings: SettingsRepository by inject()
    private val controller: SyncController by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (cameras.current() != null && !settings.settings.first().syncPaused) controller.start()
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The system wakes this service when the paired camera comes into Bluetooth range, even if the
 * app was killed – this is what makes the connection feel "always on" without polling.
 */
class CameraPresenceService : CompanionDeviceService(), KoinComponent {
    private val controller: SyncController by inject()
    private val connection: CameraConnection by inject()
    private val settings: SettingsRepository by inject()
    private val log: DiagnosticsLog by inject()

    private fun cameraAppeared() {
        // Paused means left alone: not even a brief start of the service.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            if (settings.settings.first().syncPaused) return@launch
            log.log("System saw the camera, waking")
            controller.start()
            connection.reconnectNow()
        }
    }

    /** API 36+ delivers presence here for a request-based observer. */
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED || event.event == DevicePresenceEvent.EVENT_BT_CONNECTED) cameraAppeared()
    }

    @Deprecated("Replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = cameraAppeared()

    @Deprecated("API 33 replacement is the AssociationInfo overload")
    override fun onDeviceAppeared(address: String) = cameraAppeared()
}

/** A Companion Device association: what lets the system watch for the camera while the app is not running. */
class CompanionPresence(private val context: Context, private val log: DiagnosticsLog) : CameraPresence {
    private val cdm get() = context.getSystemService(CompanionDeviceManager::class.java)
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes

    private fun association(address: String) =
        cdm?.myAssociations?.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }

    override fun isAssociated(address: String) = association(address) != null

    override suspend fun requestAssociation(address: String): IntentSender? = suspendCancellableCoroutine { cont ->
        val manager = cdm
        if (manager == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        // A classic-device filter on purpose: it also matches bonded devices, and the camera stops advertising while connected.
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        manager.associate(request, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                if (cont.isActive) cont.resume(intentSender)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                log.log("Companion association created")
                observe(address)
                _changes.value++
                if (cont.isActive) cont.resume(null)
            }

            override fun onFailure(error: CharSequence?) {
                log.log("Companion association failed: $error")
                if (cont.isActive) cont.resume(null)
            }
        }, Handler(Looper.getMainLooper()))
    }

    override fun observe(address: String) {
        val association = association(address) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(address)
            }
        }.onFailure { log.log("Presence observing failed: ${it.message}") }
    }

    override fun forget(address: String) {
        val association = association(address) ?: return
        runCatching { cdm.disassociate(association.id) }.onFailure { log.log("Disassociate failed: ${it.message}") }
        _changes.value++
    }
}

/** Safety net: every 15 minutes make sure the service is alive if a camera is paired. */
class KeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val cameras: CameraRepository by inject()
    private val settings: SettingsRepository by inject()
    private val controller: SyncController by inject()

    override suspend fun doWork(): Result {
        if (!CameraSyncService.running && cameras.current() != null && !settings.settings.first().syncPaused) {
            controller.start()
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "keep-alive",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
