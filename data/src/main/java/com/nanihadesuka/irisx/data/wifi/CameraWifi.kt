package com.nanihadesuka.irisx.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address

/** A joined camera access point. */
class CameraNetwork(
    val network: Network,
    val ssid: String?,
    val is5Ghz: Boolean,
    val gateway: String,
    val localAddress: String,
)

/**
 * Joins the camera's own Wi-Fi access point as a local-only network, next to the phone's normal
 * Wi-Fi. Every callback of the network request is logged (tag IrisXDiag) in full.
 */
class CameraWifi(context: Context, private val log: DiagnosticsLog) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    suspend fun <T> join(
        ssid: String?,
        passphrase: String?,
        bssid: String? = null,
        block: suspend (CameraNetwork) -> T,
    ): T {
        val specifier = WifiNetworkSpecifier.Builder().apply {
            if (ssid != null) setSsidPattern(PatternMatcher(ssid, PatternMatcher.PATTERN_LITERAL)) else setSsidPattern(PatternMatcher(SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            if (!bssid.isNullOrEmpty()) setBssid(android.net.MacAddress.fromString(bssid))
            if (!passphrase.isNullOrEmpty()) setWpa2Passphrase(passphrase)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        log.log("WIFI> requestNetwork ssid=${ssid ?: "pattern $SSID_PREFIX*"} bssid=${bssid ?: "any"} passphrase=${if (passphrase.isNullOrEmpty()) "none" else "set(${passphrase.length} chars)"}")
        log.log("WIFI   request=$request")
        logDefaultNetwork("before join")

        val available = CompletableDeferred<CameraNetwork>()
        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            private var name: String? = null
            private var is5 = false
            private var caps: NetworkCapabilities? = null
            private var props: LinkProperties? = null

            override fun onAvailable(network: Network) {
                log.log("WIFI< onAvailable $network")
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                log.log("WIFI< onLosing $network in ${maxMsToLive}ms")
            }

            override fun onLost(network: Network) {
                log.log("WIFI< onLost $network")
            }

            override fun onUnavailable() {
                log.log("WIFI< onUnavailable (request failed or timed out)")
                available.completeExceptionally(IllegalStateException("Camera Wi-Fi unavailable"))
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                log.log("WIFI< onBlockedStatusChanged $network blocked=$blocked")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiInfo
                name = info?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }
                is5 = (info?.frequency ?: 0) >= 4900
                this.caps = caps
                log.log(
                    "WIFI< capabilities $network ssid=${info?.ssid} bssid=${info?.bssid} freq=${info?.frequency}MHz " +
                        "rssi=${info?.rssi} txMbps=${info?.txLinkSpeedMbps} rxMbps=${info?.rxLinkSpeedMbps} " +
                        "internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                        "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                        "notRestricted=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)}",
                )
                tryComplete(network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                props = linkProperties
                log.log(
                    "WIFI< linkProperties $network if=${linkProperties.interfaceName} " +
                        "addrs=${linkProperties.linkAddresses} routes=${linkProperties.routes} " +
                        "dns=${linkProperties.dnsServers} dhcp=${linkProperties.dhcpServerAddress}",
                )
                tryComplete(network)
            }

            /** Only done once the camera has also handed us an IPv4 address and a gateway. */
            private fun tryComplete(network: Network) {
                val c = caps ?: return
                val lp = props ?: return
                val isCamera = when {
                    ssid != null -> name == null || name == ssid
                    name != null -> name!!.startsWith(SSID_PREFIX, ignoreCase = true)
                    else -> !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                if (!isCamera) {
                    log.log("WIFI   ignoring $network: ssid '$name' is not the camera")
                    return
                }
                val local = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull() ?: return
                val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
                    ?: lp.dhcpServerAddress?.hostAddress
                    ?: return
                if (!available.isCompleted) {
                    log.log("WIFI   camera network ready: local=${local.hostAddress} gateway=$gateway")
                    available.complete(CameraNetwork(network, name, is5, gateway, local.hostAddress ?: ""))
                }
            }
        }
        // bindProcessToNetwork(null) right before the request.
        cm.bindProcessToNetwork(null)
        cm.requestNetwork(request, callback)
        val began = System.currentTimeMillis()
        try {
            val net = withTimeout(JOIN_TIMEOUT_MS) { available.await() }
            log.log("WIFI   joined in ${System.currentTimeMillis() - began} ms")
            logDefaultNetwork("after join")
            // Bind the whole process to the
            // camera network AND use that network's socket factory for the sockets.
            log.log("WIFI> bindProcessToNetwork ${cm.bindProcessToNetwork(net.network)}")
            return block(net)
        } finally {
            cm.bindProcessToNetwork(null)
            log.log("WIFI> unregisterNetworkCallback")
            cm.unregisterNetworkCallback(callback)
        }
    }

    private fun logDefaultNetwork(label: String) {
        val active = cm.activeNetwork
        val props = cm.getLinkProperties(active)
        log.log("WIFI   [$label] default network=$active if=${props?.interfaceName} addrs=${props?.linkAddresses}")
    }

    companion object {
        /** Fujifilm cameras broadcast an SSID like `FUJIFILM-X100VI-1A2B`. */
        const val SSID_PREFIX = "FUJIFILM-"
        private const val JOIN_TIMEOUT_MS = 30_000L
    }
}
