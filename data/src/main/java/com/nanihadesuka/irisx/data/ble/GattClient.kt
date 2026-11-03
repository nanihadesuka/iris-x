package com.nanihadesuka.irisx.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Thin coroutine wrapper over [BluetoothGatt]. One in-flight GATT operation at a time
 * (Android only supports that), each with a timeout so a stuck radio never hangs the service.
 * Every call and every callback is logged (tag IrisXDiag) so the radio conversation is visible.
 */
@SuppressLint("MissingPermission")
internal class GattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: DiagnosticsLog,
    /** False while pairing: the MTU exchange and service discovery must wait until the bond exists. */
    private val startMtuOnConnect: Boolean = true,
) {
    class Notification(val uuid: UUID, val value: ByteArray)

    private val _notifications = MutableSharedFlow<Notification>(replay = 32, extraBufferCapacity = 64)
    val notifications: SharedFlow<Notification> = _notifications.asSharedFlow()

    private val connected = CompletableDeferred<Unit>()
    private val mtuDone = CompletableDeferred<Int>()
    private val servicesDone = CompletableDeferred<Int>()
    @Volatile private var discoverStarted = false

    /** Completes with the GATT status when the link drops. */
    val disconnected = CompletableDeferred<Int>()

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var pending: CompletableDeferred<Int>? = null
    private var readResult: ByteArray = ByteArray(0)

    private fun short(u: UUID) = u.toString().take(8)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log.log("GATT< connectionState status=$status newState=${stateName(newState)} bond=${device.bondState}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // requestMtu(185) first; services are discovered from onMtuChanged.
                if (startMtuOnConnect) beginMtuExchange()
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected.complete(status)
                connected.completeExceptionally(IllegalStateException("Disconnected ($status)"))
                // Fail the waiting steps right away instead of letting them time out (and never retry).
                mtuDone.completeExceptionally(IllegalStateException("Disconnected ($status) before the MTU exchange finished"))
                servicesDone.completeExceptionally(IllegalStateException("Disconnected ($status) before service discovery finished"))
                pending?.complete(-1)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log.log("GATT< servicesDiscovered status=$status count=${g.services.size}")
            g.services.forEach { s ->
                log.log("GATT   service ${s.uuid}")
                s.characteristics.forEach { c -> log.log("GATT     char ${short(c.uuid)} props=0x${Integer.toHexString(c.properties)}") }
            }
            servicesDone.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log.log("GATT< mtu=$mtu status=$status")
            // discoverServices() once, only on success.
            if (status == BluetoothGatt.GATT_SUCCESS && !discoverStarted) {
                discoverStarted = true
                log.log("GATT> discoverServices (from onMtuChanged) accepted=${g.discoverServices()}")
            }
            mtuDone.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            log.log("GATT< write done ${short(c.uuid)} status=$status")
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log.log("GATT< descriptor write done ${short(d.characteristic.uuid)} status=$status")
            pending?.complete(status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            log.log("GATT< read ${short(c.uuid)} status=$status value=${value.toHex()}")
            readResult = value
            pending?.complete(status)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            readResult = c.value ?: ByteArray(0)
            log.log("GATT< read ${short(c.uuid)} status=$status value=${readResult.toHex()}")
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(Notification(c.uuid, value))
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            _notifications.tryEmit(Notification(c.uuid, c.value ?: ByteArray(0)))
        }
    }

    private fun stateName(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> s.toString()
    }

    /**
     * Connects. With [autoConnect] the OS keeps a very low duty-cycle background scan and
     * connects the moment the camera advertises, the most power-efficient way to stay linked.
     */
    suspend fun connect(autoConnect: Boolean, timeoutMs: Long = Long.MAX_VALUE) {
        log.log("GATT> connectGatt ${device.address} autoConnect=$autoConnect bond=${device.bondState}")
        // connectGatt(context, false, callback) (default transport), then gatt.connect().
        gatt = device.connectGatt(context, autoConnect, callback)
        if (!autoConnect) gatt?.connect()
        if (timeoutMs == Long.MAX_VALUE) connected.await() else withTimeout(timeoutMs) { connected.await() }
    }

    /** requestMtu(185) first; onMtuChanged then starts service discovery. */
    fun beginMtuExchange() {
        val mtuAsked = gatt?.requestMtu(CAMERA_MTU) ?: false
        log.log("GATT> requestMtu($CAMERA_MTU) accepted=$mtuAsked")
    }

    /** Waits for the MTU exchange first, then service discovery (both driven from callbacks). */
    suspend fun awaitMtuAndServices() {
        val mtu = withTimeout(OP_TIMEOUT_MS) { mtuDone.await() }
        check(mtu == BluetoothGatt.GATT_SUCCESS) { "MTU exchange failed ($mtu)" }
        val services = withTimeout(OP_TIMEOUT_MS) { servicesDone.await() }
        check(services == BluetoothGatt.GATT_SUCCESS) { "Service discovery failed ($services)" }
    }

    suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        val c = characteristic(service, characteristic)
        op("read ${short(characteristic)}") { gatt!!.readCharacteristic(c) }
        return readResult
    }

    @Suppress("DEPRECATION")
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        val c = characteristic(service, characteristic)
        op("write ${short(characteristic)} ${value.toHex()}") {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt!!.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = value
                gatt!!.writeCharacteristic(c)
            }
        }
    }

    @Suppress("DEPRECATION")
    suspend fun subscribe(service: UUID, characteristic: UUID, indicate: Boolean) {
        val c = characteristic(service, characteristic)
        gatt!!.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: error("No CCCD on $characteristic")
        val enable = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op("subscribe ${short(characteristic)} ${if (indicate) "indicate" else "notify"}") {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt!!.writeDescriptor(cccd, enable) == 0
            } else {
                cccd.value = enable
                gatt!!.writeDescriptor(cccd)
            }
        }
    }

    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    fun close() {
        log.log("GATT> close")
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(characteristic)
            ?: error("Missing characteristic $characteristic in $service")

    /** Runs one GATT operation; [start] returns true when the stack accepted it. */
    private suspend fun op(name: String, start: () -> Boolean) {
        opLock.withLock {
            val result = CompletableDeferred<Int>()
            pending = result
            val began = System.currentTimeMillis()
            val accepted = start()
            log.log("GATT> $name accepted=$accepted")
            check(accepted) { "GATT op refused: $name" }
            val status = withTimeout(OP_TIMEOUT_MS) { result.await() }
            pending = null
            log.log("GATT   $name finished status=$status in ${System.currentTimeMillis() - began} ms")
            check(status == BluetoothGatt.GATT_SUCCESS) { "GATT op failed: $name (status $status)" }
        }
    }

    private companion object {
        const val OP_TIMEOUT_MS = 10_000L
        const val CAMERA_MTU = 185
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
