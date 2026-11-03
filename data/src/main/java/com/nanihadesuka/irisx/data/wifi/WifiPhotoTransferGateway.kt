package com.nanihadesuka.irisx.data.wifi

import android.os.Build
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import com.nanihadesuka.irisx.camera.api.PhotoSession
import com.nanihadesuka.irisx.domain.repository.PhotoTransferGateway
import com.nanihadesuka.irisx.domain.repository.SyncActivity
import com.nanihadesuka.irisx.domain.model.TransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Joins the camera's Wi-Fi (5 GHz preferred), opens PTP/IP over that specific network, runs
 * the caller's block, then tears everything down so the phone returns to its normal network.
 */
class WifiPhotoTransferGateway(
    private val appContext: android.content.Context,
    private val wifi: CameraWifi,
    private val log: DiagnosticsLog,
    private val activity: SyncActivity,
    private val backend: com.nanihadesuka.irisx.camera.api.CameraBackend,
) : PhotoTransferGateway {
    override val wifiEnabled: Boolean
        get() = appContext.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled != false


    /** The camera takes one session: an auto transfer and a browse session must take turns. */
    private val sessionLock = kotlinx.coroutines.sync.Mutex()

    override suspend fun <T> withSession(camera: Camera, purpose: com.nanihadesuka.irisx.camera.api.SessionPurpose, block: suspend (PhotoSession) -> T): T =
        sessionLock.withLock { openSession(camera, purpose, block) }

    private suspend fun <T> openSession(camera: Camera, purpose: com.nanihadesuka.irisx.camera.api.SessionPurpose, block: suspend (PhotoSession) -> T): T {
        var lastError: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                activity.set(TransferState.JoiningWifi(attempt + 1))
                return wifi.join(camera.wifiSsid, camera.wifiPassphrase, camera.wifiBssid) { net ->
                  try {
                    activity.set(TransferState.WifiConnected())
                    log.log("Wi-Fi up (${if (net.is5Ghz) "5 GHz" else "2.4 GHz"}) phone ${net.localAddress} camera ${net.gateway}")
                    backend.open(
                        com.nanihadesuka.irisx.camera.api.JoinedNetwork(net.network.socketFactory, net.network), com.nanihadesuka.irisx.data.ble.FujiProtocol.clientName(appContext), purpose,
                        onAttempt = { activity.set(TransferState.WifiConnected(it)) },
                    ) { log.log(it) }.use { block(it) }
                  } catch (e: CancellationException) {
                      throw e
                  } catch (e: Exception) {
                      // The Wi-Fi is up; a failure inside the session must NOT re-request the network (that re-triggers
                      // the Android prompt). Only re-request when the join itself fails (onUnavailable).
                      throw SessionFailure(e)
                  }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionFailure) {
                throw e.cause as Exception
            } catch (e: Exception) {
                lastError = e
                log.log("Transfer attempt ${attempt + 1} failed: ${e.message}")
                delay(RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Transfer failed")
    }

    private class SessionFailure(cause: Exception) : Exception(cause)

    private companion object {
        const val ATTEMPTS = 4
        const val RETRY_DELAY_MS = 58L // observed gap between the failed request and the next requestNetwork
    }
}
