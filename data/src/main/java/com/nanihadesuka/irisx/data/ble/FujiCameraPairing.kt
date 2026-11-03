package com.nanihadesuka.irisx.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.nanihadesuka.irisx.data.wifi.CameraWifi
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.model.CameraCandidate
import com.nanihadesuka.irisx.domain.model.PairingState
import com.nanihadesuka.irisx.domain.model.PairingStep
import com.nanihadesuka.irisx.domain.repository.CameraPairing
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

/**
 * Pairing = (1) find the camera in "Pairing Register" mode, (2) create the BLE bond,
 * (3) run the Fuji handshake so the camera registers this phone, (4) remember the camera's
 * Wi-Fi network by joining it once.
 */
@SuppressLint("MissingPermission")
class FujiCameraPairing(
    private val context: Context,
    private val cameras: CameraRepository,
    private val wifi: CameraWifi,
    private val log: DiagnosticsLog,
) : CameraPairing {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    override val state: StateFlow<PairingState> = _state

    private val found = linkedMapOf<String, CameraCandidate>()
    private var scanCallback: ScanCallback? = null

    override suspend fun scan() {
        val scanner = manager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = PairingState.Failed("Bluetooth is off")
            return
        }
        found.clear()
        _state.value = PairingState.Scanning
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val fuji = record.getManufacturerSpecificData(FujiProtocol.COMPANY_ID) != null
                if (!fuji) return
                val name = result.device.name ?: record.deviceName ?: "FUJIFILM camera"
                found[result.device.address] = CameraCandidate(result.device.address, name, result.rssi)
                _state.value = PairingState.Found(found.values.sortedByDescending { it.signalDbm })
            }

            override fun onScanFailed(errorCode: Int) {
                _state.value = PairingState.Failed("Scan failed ($errorCode)")
            }
        }
        scanCallback = callback
        val filters = listOf(ScanFilter.Builder().setManufacturerData(FujiProtocol.COMPANY_ID, byteArrayOf()).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        try {
            awaitCancellation()
        } finally {
            stopScan()
        }
    }

    override fun stopScan() {
        scanCallback?.let { manager.adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    override suspend fun pair(candidate: CameraCandidate) {
        stopScan()
        val device = manager.adapter.getRemoteDevice(candidate.bleAddress)
        val gatt = GattClient(context, device, log, startMtuOnConnect = false)
        try {
            _state.value = PairingState.Working(PairingStep.Bluetooth)
            gatt.connect(autoConnect = false, timeoutMs = 20_000)
            if (device.bondState != BluetoothDevice.BOND_BONDED) awaitBond(device)
            // Bond first, then MTU 185 -> onMtuChanged -> discoverServices.
            gatt.beginMtuExchange()
            gatt.awaitMtuAndServices()

            _state.value = PairingState.Working(PairingStep.Identify)
            val hs = gatt.fujiHandshake(FujiProtocol.clientName(context), log, storedIdentification = 0, pairing = true)
            _state.value = PairingState.Working(PairingStep.Subscribe)

            _state.value = PairingState.Working(PairingStep.Wifi)
            val ssid = hs.ssid
            val pass = hs.passphrase

            val camera = Camera(candidate.bleAddress, candidate.name, wifiSsid = ssid, wifiPassphrase = pass, wifiBssid = hs.bssid, identificationNumber = hs.identification)
            cameras.save(camera)
            log.log("Paired with ${camera.name}")
            _state.value = PairingState.Done(camera)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log("Pairing failed: ${e.message}")
            _state.value = PairingState.Failed(e.message ?: "Pairing failed")
        } finally {
            gatt.close()
        }
    }

    override fun removeBond(address: String) {
        // Hidden platform API; there is no public way to remove a bond. The camera keeps its own registration.
        runCatching {
            val device = manager.adapter.getRemoteDevice(address)
            val removed = device.javaClass.getMethod("removeBond").invoke(device)
            log.log("Bluetooth bond removal requested: $removed")
        }.onFailure { log.log("Bluetooth bond removal failed: ${it.message}") }
    }

    override fun reset() {
        stopScan()
        _state.value = PairingState.Idle
    }

    /** Starts bonding and waits (up to 60 s) for the user to confirm on the camera. */
    private suspend fun awaitBond(device: BluetoothDevice) {
        val bonded = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(Unit)
                    BluetoothDevice.BOND_NONE -> bonded.completeExceptionally(IllegalStateException("Pairing was declined"))
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        try {
            if (!device.createBond()) error("Could not start Bluetooth pairing")
            withTimeout(60_000) { bonded.await() }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
