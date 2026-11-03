package com.nanihadesuka.irisx.data.background

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nanihadesuka.irisx.domain.repository.CameraConnection
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import com.nanihadesuka.irisx.domain.repository.SyncActivity
import com.nanihadesuka.irisx.domain.repository.SyncController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Foreground service that hosts the [SyncEngine]. Type `connectedDevice` (BLE link) plus
 * `location` when the permission is available (phone location → camera).
 */
class CameraSyncService : LifecycleService(), KoinComponent {
    private val engine: SyncEngine by inject()
    private val notifications: SyncNotifications by inject()
    private val connection: CameraConnection by inject()
    private val activity: SyncActivity by inject()
    private val cameras: CameraRepository by inject()
    private val settings: SettingsRepository by inject()
    private val log: DiagnosticsLog by inject()
    private var engineJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        if (engineJob?.isActive != true) {
            running = true
            engineJob = lifecycleScope.launch {
                if (cameras.current() == null || settings.settings.first().syncPaused) {
                    stopSelf()
                    return@launch
                }
                launch {
                    combine(connection.state, activity.transfer) { c, t -> notifications.status(c, t) }
                        .collect { getSystemService(NotificationManager::class.java).notify(SyncNotifications.STATUS_ID, it) }
                }
                // A failure in one engine loop must not leave a foreground service that does nothing.
                while (true) {
                    try {
                        engine.run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.log("Engine stopped, restarting: ${e.message}")
                    }
                    delay(ENGINE_RESTART_MS)
                }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        val notification = notifications.status(connection.state.value, activity.transfer.value)
        try {
            startForeground(SyncNotifications.STATUS_ID, notification, type)
        } catch (e: RuntimeException) {
            // Android refuses the location type when started from the background (boot, worker). The link matters more.
            log.log("Foreground without location type: ${e.message}")
            startForeground(SyncNotifications.STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val ENGINE_RESTART_MS = 5_000L

        @Volatile var running = false
            private set
    }
}

class SyncServiceController(private val context: Context) : SyncController {
    override fun start() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, CameraSyncService::class.java)) }
    }

    override fun stop() {
        context.stopService(Intent(context, CameraSyncService::class.java))
    }
}
