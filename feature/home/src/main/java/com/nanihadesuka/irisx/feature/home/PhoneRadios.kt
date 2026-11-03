package com.nanihadesuka.irisx.feature.home

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class Radio { Bluetooth, Wifi }

data class PhoneRadios(val bluetoothOff: Boolean, val wifiOff: Boolean)

private fun Context.readRadios() = PhoneRadios(
    // A phone with no adapter cannot be asked to turn one on.
    bluetoothOff = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == false,
    wifiOff = getSystemService(WifiManager::class.java)?.isWifiEnabled == false,
)

/** Follows the phone's Bluetooth and Wi-Fi switches for as long as the screen is shown. */
@Composable
fun rememberPhoneRadios(): PhoneRadios {
    val context = LocalContext.current
    var radios by remember { mutableStateOf(context.readRadios()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                radios = c.readRadios()
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            },
        )
        radios = context.readRadios()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return radios
}

/** The system's own small prompt for each radio. Only ever opened by a tap: apps may not switch these themselves. */
@Composable
fun rememberTurnOnRadio(): (Radio) -> Unit {
    val messages = LocalAppMessages.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    return { radio ->
        try {
            launcher.launch(
                when (radio) {
                    Radio.Bluetooth -> Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    Radio.Wifi -> Intent(Settings.Panel.ACTION_WIFI)
                },
            )
        } catch (e: RuntimeException) {
            messages.show(if (radio == Radio.Bluetooth) "Turn on Bluetooth in the phone's settings" else "Turn on Wi-Fi in the phone's settings")
        }
    }
}
