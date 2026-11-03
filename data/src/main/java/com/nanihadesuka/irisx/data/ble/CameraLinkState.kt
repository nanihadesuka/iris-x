package com.nanihadesuka.irisx.data.ble

/** Camera-reported BLE state shared with the Wi-Fi side, read before opening PTP/IP. */
object CameraLinkState {
    @Volatile var apState = 0
    @Volatile var transferable = false
}
