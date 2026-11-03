package com.nanihadesuka.irisx.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nanihadesuka.irisx.domain.model.Attention
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.LocationFix
import com.nanihadesuka.irisx.domain.repository.CameraConnection
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/** What the camera told us during the session. */
internal class HandshakeResult(
    val bssid: String?,
    val ssid: String?,
    val passphrase: String?,
    val apState: Int,
    val transferable: Boolean,
    /** The identification number that was written to the camera (stored one, or camera number | 0x20000000). */
    val identification: Int,
)

/**
 * The Bluetooth session with the camera, in the order it expects
 * (same operations, same order, same values). Reads are part of it on purpose: the camera may
 * track what a client has looked at. Only the camera-dependent values are computed:
 *  - the status acknowledgement (copy of the status read, last byte 0x20),
 *  - the null-terminated phone name,
 *  - the UTC/timezone record.
 */
internal suspend fun GattClient.fujiHandshake(
    phoneName: String,
    log: DiagnosticsLog,
    storedIdentification: Int,
    pairing: Boolean,
): HandshakeResult {
    val P = FujiProtocol
    val S_GENERIC_ACCESS = java.util.UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    val S_DEVICE_INFO = java.util.UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    fun u(s: String) = java.util.UUID.fromString(s)
    val SERIAL = u("00002a25-0000-1000-8000-00805f9b34fb")
    val DEVICE_NAME = u("00002a00-0000-1000-8000-00805f9b34fb")
    val FIRMWARE = u("00002a26-0000-1000-8000-00805f9b34fb")
    val INFO_SERVICE = P.ADVERTISED_SERVICE                                  // a9d2b304
    val CAMERA_MAC = u("49a12959-dfaa-4eb2-89ce-62548ad948f3")
    val CAMERA_SERIAL = u("e8e40d50-a625-4f1d-96ed-8cec034f5690")
    val CAMERA_Y = u("27870478-94a9-4345-849b-efa3bf37887f")
    val POWER_KEY_SERVICE = u("804daa8e-ffeb-4ab3-8e75-6edd7303208d")
    val POWER_KEY_STATE = u("f90f7d3a-3b64-45c6-ab21-933900184837")
    val LOGGING = u("75823784-fbb7-4b71-abae-cd9a34072e3c")
    val LOC_SETTING = u("aab609c4-94dd-4d89-bc60-665d5090b828")
    val LENS_SERVICE = u("15ca59fe-620c-464d-a987-223fab660cde")
    val LENS_NAME = u("68887a7a-10a7-4a4a-aadd-6f9db1abaa0e")
    val LENS_FW = u("621a98d8-6314-4c3e-a683-eea1d6166606")
    val LENS_SERIAL = u("df287e0c-c23f-4f80-afdd-c2b570288cdb")
    val REMOTE_BOOT = u("7170fd5a-56d9-4c19-b043-7a7047d8e1a0")
    val VITAL = P.NOT6
    val RESIZE_SETTING = P.NOT9                                              // 82a9f452
    val SHOOTING_REQUEST = u("7fcf49c6-4ff0-4777-a03d-1a79166af7a8")

    var ssid: String? = null
    var pass: String? = null
    var bssid: String? = null
    var ap = 0
    var transferable = false

    suspend fun step(label: String, block: suspend () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log.log("STEP $label failed: ${e.message}") }
    }
    fun ByteArray.text() = String(this, Charsets.UTF_8).trim { it <= ' ' }.ifEmpty { null }

    // --- 1. Device name and pairing identification number ---
    // The client name is written first ONLY while pairing (PairingMode.PAIRING); a reconnect skips it here.
    if (pairing) write(P.PAIR_SERVICE, P.IDENT_CHAR, phoneName.toByteArray(Charsets.UTF_8) + 0)
    // Read the identification number the camera offers (retry once a second, at most 20 times).
    var offered: ByteArray? = null
    for (attempt in 1..20) {
        val v = read(P.PAIR_SERVICE, P.STATUS_CHAR)
        if (v.size == 4) { offered = v; break }
        log.log("Identification number has ${v.size} bytes, waiting 1000 ms (attempt $attempt)")
        delay(1_000)
    }
    check(offered != null) { "Camera never returned an identification number" }
    val offeredNumber = java.nio.ByteBuffer.wrap(offered).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    // No stored number -> camera number | 0x20000000, otherwise the STORED one.
    val identification = if (storedIdentification == 0) offeredNumber or 0x20000000 else storedIdentification
    log.log("Identification: camera offered 0x%08x, stored 0x%08x -> writing 0x%08x".format(offeredNumber, storedIdentification, identification))
    write(P.PAIR_SERVICE, P.STATUS_CHAR, java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(identification).array())
    // --- 2. identify ---
    step("serial") { read(S_DEVICE_INFO, SERIAL) }
    step("device name") { read(S_GENERIC_ACCESS, DEVICE_NAME) }
    write(P.PAIR_SERVICE, P.IDENT_CHAR, phoneName.toByteArray(Charsets.UTF_8) + 0)
    // --- 3. camera info reads ---
    step("camera mac") { bssid = read(INFO_SERVICE, CAMERA_MAC).text()?.replace("-", ":") }
    step("firmware") { read(S_DEVICE_INFO, FIRMWARE) }
    step("camera serial") { read(INFO_SERVICE, CAMERA_SERIAL) }
    step("camera y") { read(INFO_SERVICE, CAMERA_Y) }
    step("passphrase") { pass = read(P.NOTX_SERVICE, P.PASSPHRASE_CHAR).text() }
    // --- 4. first subscriptions: AP state, transfer state (indicate), date sync, location (notify) ---
    step("sub ap state") { subscribe(P.CONF_SERVICE, P.AP_STATE, indicate = true) }
    step("sub transfer state") { subscribe(P.CONF_SERVICE, P.TRANSFER_STATE, indicate = true) }
    step("sub date sync") { subscribe(P.CONF_SERVICE, P.NOT1, indicate = false) }
    step("sub location") { subscribe(P.CONF_SERVICE, P.GEOTAG_REQUEST, indicate = false) }
    step("ssid") { ssid = read(P.NOTX_SERVICE, P.SSID_CHAR).text() }
    // Pause here before continuing.
    delay(5_700)
    // --- 5. state reads ---
    step("location setting") { read(P.NOTX_SERVICE, LOC_SETTING) }
    step("ap state") { ap = FujiProtocol.u16(read(P.CONF_SERVICE, P.AP_STATE)) ?: 0 }
    step("logging setting") { read(P.NOTX_SERVICE, LOGGING) }
    step("power key state") { read(POWER_KEY_SERVICE, POWER_KEY_STATE) }
    step("transfer state") { transferable = FujiProtocol.u16(read(P.CONF_SERVICE, P.TRANSFER_STATE)) == FujiProtocol.TRANSFERABLE }
    step("image transfer setting EX") { write(P.NOTX_SERVICE, P.IMAGE_TRANSFER_SETTING_EX, byteArrayOf(1)) }
    step("shooting request on") { write(P.CONTROL_SERVICE, SHOOTING_REQUEST, byteArrayOf(1, 0)) }
    step("lens name") { read(LENS_SERVICE, LENS_NAME) }
    step("lens firmware") { read(LENS_SERVICE, LENS_FW) }
    step("camera serial 2") { read(INFO_SERVICE, CAMERA_SERIAL) }
    step("sub remote boot") { subscribe(POWER_KEY_SERVICE, REMOTE_BOOT, indicate = false) }
    step("lens serial") { read(LENS_SERVICE, LENS_SERIAL) }
    step("remote boot") { read(POWER_KEY_SERVICE, REMOTE_BOOT) }
    // --- 6. second subscriptions ---
    step("sub ssid") { subscribe(P.NOTX_SERVICE, P.SSID_CHAR, indicate = false) }
    step("sub logging") { subscribe(P.NOTX_SERVICE, LOGGING, indicate = false) }
    step("sub vital") { subscribe(P.CONF_SERVICE, VITAL, indicate = false) }
    step("sub location setting") { subscribe(P.NOTX_SERVICE, LOC_SETTING, indicate = false) }
    step("sub resize setting") { subscribe(P.NOTX_SERVICE, RESIZE_SETTING, indicate = false) }
    step("shooting request off") { write(P.CONTROL_SERVICE, SHOOTING_REQUEST, byteArrayOf(0, 0)) }
    step("sub sync cycle") { subscribe(P.NOTX_SERVICE, P.GEOTAG_SYNC_INTERVAL, indicate = false) }
    // --- 7. configuration writes ---
    write(P.NOTX_SERVICE, P.GEOTAG_SYNC_INTERVAL, P.geotagIntervalBytes())
    step("utc and timezone") { write(P.TIME_SERVICE, P.UTC_TZ_CHAR, P.encodeUtcAndTimezone(System.currentTimeMillis())) }
    log.log("Handshake replay done: ssid=$ssid ap=0x%04x transferable=$transferable".format(ap))
    return HandshakeResult(bssid, ssid, pass, ap, transferable, identification)
}

/**
 * Keeps one bonded camera connected with the OS-level "auto connect" mode, which uses a
 * duty-cycled background scan: near-zero battery while the camera is off or away, and an
 * immediate connection as soon as it powers on.
 */
@SuppressLint("MissingPermission")
class FujiCameraConnection(
    private val context: Context,
    private val log: DiagnosticsLog,
    private val cameras: CameraRepository,
) : CameraConnection {

    private val manager = context.getSystemService(BluetoothManager::class.java)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NotPaired)
    override val state: StateFlow<ConnectionState> = _state

    private val _newPhoto = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val newPhotoSignals: Flow<Unit> = _newPhoto.asSharedFlow()

    private val _locationRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val locationRequests: Flow<Unit> = _locationRequests.asSharedFlow()

    private val _lastLocationSent = MutableStateFlow<Long?>(null)
    override val lastLocationSentMillis: StateFlow<Long?> = _lastLocationSent

    // replay = 1: the waiter may start collecting after the camera already reported the access point.
    private val _apLaunched = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val accessPointLaunched: Flow<Unit> = _apLaunched.asSharedFlow()

    private val _lastConnected = MutableStateFlow<Long?>(null)
    override val lastConnectedMillis: StateFlow<Long?> = _lastConnected

    @Volatile private var client: GattClient? = null

    /** Ends a backoff or a low-power scan early; the next search runs at full speed. */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    @Volatile private var fastScansLeft = FAST_SCANS

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) = reconnectNow()
    }

    // Camera-reported readiness (wait for BOTH before opening PTP/IP).
    private var apState: Int
        get() = CameraLinkState.apState
        set(v) { CameraLinkState.apState = v }
    private var transferable: Boolean
        get() = CameraLinkState.transferable
        set(v) { CameraLinkState.transferable = v }

    @Volatile private var accessPointRequested = false

    override val offeringTransfer: Boolean get() = apState == FujiProtocol.AP_RESERVED_TRANSFER

    /**
     * The ONLY trigger of an automatic transfer is an AP-state value of 0x8003
     * (launched for reserved image transfer) that differs from the previous AP state, or a repeated 0x8003 while
     * the transfer state is Transferable. The transfer-state notification alone never starts anything, and the
     * Do not wait for Transferable before joining the Wi-Fi.
     */
    private fun onApState(new: Int, reason: String) {
        val prev = apState
        apState = new
        val auto = new == FujiProtocol.AP_RESERVED_TRANSFER && (prev != new || transferable)
        log.log("AP state 0x%04x -> 0x%04x (%s) transferable=%s autoTransfer=%s".format(prev, new, reason, transferable, auto))
        if (auto) _newPhoto.tryEmit(Unit)
        // The camera's AP is up after a FunctionLaunchRequest.
        if (new == FujiProtocol.AP_LAUNCHED && accessPointRequested) {
            accessPointRequested = false
            log.log("Camera access point launched on request")
            _apLaunched.tryEmit(Unit)
        }
    }

    /** Debug hook for scripted tests: `adb shell am broadcast -a com.nanihadesuka.irisx.DEBUG_FETCH [--ez force true|--ez emit true]`. */
    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            log.log("DEBUG broadcast force=${i.getBooleanExtra("force", false)} emit=${i.getBooleanExtra("emit", false)}")
            if (i.getBooleanExtra("status", false)) { log.log("DEBUG status state=${_state.value} ap=0x%04x transferable=$transferable apRequested=$accessPointRequested client=${client != null}".format(apState)); return }
            if (i.getBooleanExtra("reset", false)) { accessPointRequested = false; apState = 0; transferable = false; log.log("DEBUG reset: closing BLE link"); client?.close(); return }
            if (i.getBooleanExtra("emit", false)) { _newPhoto.tryEmit(Unit); return }
            val gatt = client ?: return
            accessPointRequested = true
            Thread {
                runCatching { kotlinx.coroutines.runBlocking { gatt.write(FujiProtocol.CONTROL_SERVICE, FujiProtocol.FUNCTION_LAUNCH_REQUEST, FujiProtocol.u16Bytes(FujiProtocol.FUNCTION_IN_CAMERA_VIEWING)) } }
                    .onSuccess { log.log("DEBUG asked camera to start its Wi-Fi") }.onFailure { log.log("DEBUG ask failed: ${it.message}") }
            }.start()
        }
    }

    override suspend fun run(initialCamera: Camera) {
        runCatching { context.registerReceiver(debugReceiver, android.content.IntentFilter("com.nanihadesuka.irisx.DEBUG_FETCH"), Context.RECEIVER_EXPORTED) }
        context.registerReceiver(bluetoothReceiver, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        try {
            keepConnected(initialCamera)
        } finally {
            context.unregisterReceiver(bluetoothReceiver)
        }
    }

    private suspend fun keepConnected(initialCamera: Camera) {
        var attempt = 0
        // A link Android still lists can be stale, so it only gets one try before the normal scan.
        var linkShortcutTried = false
        while (currentCoroutineContext().isActive) {
            // Always the persisted registration (it carries the stored identification number).
            val camera = cameras.current() ?: initialCamera
            val adapter = manager.adapter
            when {
                !hasConnectPermission() -> { _state.value = ConnectionState.NeedsAttention(Attention.PermissionMissing); pause(RECHECK_MS); continue }
                adapter == null || !adapter.isEnabled -> { _state.value = ConnectionState.NeedsAttention(Attention.BluetoothOff); pause(RECHECK_MS); continue }
            }
            val device = adapter.getRemoteDevice(camera.bleAddress)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                _state.value = ConnectionState.NeedsAttention(Attention.PairingLost)
                pause(RECHECK_MS)
                continue
            }

            _state.value = ConnectionState.CameraAway
            val gatt = GattClient(context, device, log)
            client = gatt
            try {
                log.log("Waiting for ${camera.name}")
                // Direct connection (connectGatt autoConnect=false); the OS "auto connect"
                // mode uses background parameters and the camera drops it after ~20 s.
                // Scan first, connect from the scan result.
                // Except when the phone still has a link to it, as after a restart: the scan would only find what is here.
                val stillLinked = !linkShortcutTried && manager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT).any { it.address == camera.bleAddress }
                if (stillLinked) {
                    linkShortcutTried = true
                    log.log("Bluetooth link to ${camera.name} still up, skipping the scan")
                } else if (!scanForCamera(adapter, camera.bleAddress)) {
                    pause(backoffMs(attempt))
                    continue
                }
                gatt.connect(autoConnect = false, timeoutMs = 30_000)
                _state.value = ConnectionState.Connecting
                gatt.awaitMtuAndServices()
                val hs = gatt.fujiHandshake(FujiProtocol.clientName(context), log, camera.identificationNumber, pairing = false)
                if (hs.identification != camera.identificationNumber) {
                    cameras.save(camera.copy(identificationNumber = hs.identification))
                    log.log("Stored identification number 0x%08x".format(hs.identification))
                }
                if (hs.ssid != null && (hs.ssid != camera.wifiSsid || hs.passphrase != camera.wifiPassphrase || hs.bssid != camera.wifiBssid)) {
                    cameras.save(camera.copy(wifiSsid = hs.ssid, wifiPassphrase = hs.passphrase, wifiBssid = hs.bssid, identificationNumber = hs.identification))
                    log.log("Camera Wi-Fi: ${hs.ssid}")
                }
                apState = 0
                transferable = hs.transferable
                linkShortcutTried = false
                _state.value = ConnectionState.Connected
                _lastConnected.value = System.currentTimeMillis()
                fastScansLeft = FAST_SCANS
                log.log("Connected to ${camera.name}")
                onApState(hs.apState, "on connect")
                attempt = 0
                coroutineScope {
                    val router = launch {
                        gatt.notifications.collect { n ->
                            log.log("BLE ${n.uuid.toString().take(8)} ${n.value.toHex()}")
                            if (n.uuid == FujiProtocol.GEOTAG_REQUEST && FujiProtocol.isGeotagRequest(n.value)) {
                                log.log("Camera asked for location")
                                _locationRequests.tryEmit(Unit)
                            } else if (n.uuid == FujiProtocol.NOT1) {
                                // Date sync request: answer with the UTC/timezone record.
                                val rec = FujiProtocol.encodeUtcAndTimezone(System.currentTimeMillis())
                                runCatching { gatt.write(FujiProtocol.TIME_SERVICE, FujiProtocol.UTC_TZ_CHAR, rec) }
                                    .onSuccess { log.log("Time sync sent ${rec.toHex()}") }
                                    .onFailure { log.log("Time sync failed: ${it.message}") }
                            } else if (n.uuid == FujiProtocol.AP_STATE) {
                                FujiProtocol.u16(n.value)?.let { onApState(it, "notification") }
                            } else if (n.uuid == FujiProtocol.TRANSFER_STATE) {
                                transferable = FujiProtocol.u16(n.value) == FujiProtocol.TRANSFERABLE
                                log.log("Transfer state transferable=$transferable")
                            }
                        }
                    }
                    val status = gatt.disconnected.await()
                    router.cancel()
                    _lastConnected.value = System.currentTimeMillis()
                    log.log("Disconnected (status $status)")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // withTimeout() throws a CancellationException subclass: it is a failed step, not a cancelled loop.
                log.log("Link error (timeout): ${e.message}")
                attempt++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log("Link error: ${e.message}")
                attempt++
            } finally {
                gatt.close()
                client = null
                _state.value = ConnectionState.CameraAway
            }
            pause(backoffMs(attempt))
        }
    }

    /** Waits [ms], or less when [reconnectNow] or a Bluetooth state change arrives. */
    private suspend fun pause(ms: Long) {
        kotlinx.coroutines.withTimeoutOrNull(ms) { wake.receive() }
    }

    override fun reconnectNow() {
        fastScansLeft = FAST_SCANS
        wake.trySend(Unit)
    }

    /**
     * Reconnect scan: SCAN_MODE_LOW_LATENCY, service-UUID filters
     * FILE_TRANSFER + CONNECTED_DEVICE_INFORMATION_RED + CAMERA_STARTUP_INFORMATION(+_RED), 60 s period.
     * Returns true when an advertisement from [address] arrived; the scan is stopped before connecting.
     */
    private suspend fun scanForCamera(adapter: android.bluetooth.BluetoothAdapter, address: String): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: return false
        val seen = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                if (result.device.address.equals(address, ignoreCase = true)) {
                    log.log("SCAN< ${result.device.address} rssi=${result.rssi} uuids=${result.scanRecord?.serviceUuids}")
                    seen.complete(true)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                log.log("SCAN< failed $errorCode")
                seen.complete(false)
            }
        }
        val filters = FujiProtocol.RECONNECT_SERVICES.map {
            android.bluetooth.le.ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(it)).build()
        }
        // Iris X scans LOW_LATENCY right after a disconnect, then drops to LOW_POWER:
        // same filters, a fraction of the radio time, for a camera that may stay off for days.
        val fast = fastScansLeft > 0
        if (fast) fastScansLeft--
        val mode = if (fast) android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY else android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER
        val window = if (fast) RECONNECT_SCAN_MS else LOW_POWER_SCAN_MS
        val settings = android.bluetooth.le.ScanSettings.Builder().setScanMode(mode).build()
        log.log("SCAN> start ${if (fast) "LOW_LATENCY" else "LOW_POWER"}, ${filters.size} service filters, $window ms")
        scanner.startScan(filters, settings, callback)
        return try {
            kotlinx.coroutines.withTimeoutOrNull(window) {
                kotlinx.coroutines.selects.select<Boolean> {
                    seen.onAwait { it }
                    wake.onReceive { false }
                }
            } ?: false
        } finally {
            runCatching { scanner.stopScan(callback) } // throws when Bluetooth was just turned off
            log.log("SCAN> stopped")
        }
    }

    override suspend fun requestAccessPoint() {
        _apLaunched.resetReplayCache() // forget an older launch; only the one for THIS request counts
        val gatt = client ?: error("Camera is not connected")
        check(_state.value == ConnectionState.Connected) { "Camera is not connected" }
        // AP already launched -> straight to the session; otherwise send FunctionLaunchRequest(InCameraViewIng).
        if (apState == FujiProtocol.AP_LAUNCHED) {
            log.log("Camera access point already launched")
            _apLaunched.tryEmit(Unit)
            return
        }
        accessPointRequested = true
        gatt.write(FujiProtocol.CONTROL_SERVICE, FujiProtocol.FUNCTION_LAUNCH_REQUEST, FujiProtocol.u16Bytes(FujiProtocol.FUNCTION_IN_CAMERA_VIEWING))
        log.log("Asked camera to start its Wi-Fi (FunctionLaunchRequest InCameraViewIng)")
    }

    override fun disconnect() {
        client?.close()
    }

    override suspend fun sendLocation(fix: LocationFix) {
        val gatt = client ?: return
        if (_state.value != ConnectionState.Connected) return
        gatt.write(FujiProtocol.GEOTAG_SERVICE, FujiProtocol.GEOTAG_CHAR, FujiProtocol.encodeGeotag(fix))
        _lastLocationSent.value = System.currentTimeMillis()
        log.log("Location sent %.5f, %.5f".format(fix.latitude, fix.longitude))
    }

    private fun hasConnectPermission() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 37 || context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED)

    private fun backoffMs(attempt: Int): Long = min(1_000L shl min(attempt, 5), 30_000L)

    private companion object {
        const val RECHECK_MS = 5_000L
        const val RECONNECT_SCAN_MS = 60_000L
        const val LOW_POWER_SCAN_MS = 300_000L
        const val FAST_SCANS = 1
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
