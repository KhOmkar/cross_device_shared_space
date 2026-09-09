package com.anonymous.fileshare.service

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Manages Wi-Fi Hotspot setup and local IP discovery across Android OS versions.
 * Supports programmatic LocalOnlyHotspot on Android 8.0+ and system settings navigation.
 */
class HotspotManager(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    data class HotspotConfig(
        val ssid: String,
        val passphrase: String,
        val ipAddress: String,
        val port: Int = 8080
    )

    /**
     * Starts LocalOnlyHotspot programmatically if supported.
     */
    @Suppress("DEPRECATION")
    fun startLocalHotspot(
        onSuccess: (HotspotConfig) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && wifiManager != null) {
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        localOnlyHotspotReservation = reservation
                        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            reservation.softApConfiguration
                        } else {
                            null
                        }

                        val ssid = config?.ssid ?: "AnonymousShare-${(1000..9999).random()}"
                        val passphrase = config?.passphrase ?: (10000000..99999999).random().toString()
                        val ip = getLocalIpAddress() ?: "192.168.43.1"

                        onSuccess(HotspotConfig(ssid, passphrase, ip, 8080))
                    }

                    override fun onStopped() {
                        localOnlyHotspotReservation = null
                    }

                    override fun onFailed(reason: Int) {
                        onFailure("Local hotspot failed (code $reason). Please enable Hotspot manually.")
                    }
                }, null)
            } catch (e: SecurityException) {
                onFailure("Location permission required to start hotspot: ${e.message}")
            } catch (e: Exception) {
                onFailure("Could not start hotspot automatically: ${e.message}")
            }
        } else {
            onFailure("Automatic hotspot not supported on this device version. Please enable manually.")
        }
    }

    /**
     * Stops programmatic hotspot.
     */
    fun stopLocalHotspot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            localOnlyHotspotReservation?.close()
            localOnlyHotspotReservation = null
        }
    }

    /**
     * Opens system Tethering / Hotspot settings screen.
     */
    fun openTetheringSettings() {
        val intent = Intent().apply {
            action = "android.settings.TETHER_SETTINGS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }

    /**
     * Discovers active local IPv4 address across network interfaces, prioritizing hotspot/AP
     * and local Wi-Fi while strictly ignoring cellular (mobile data) interfaces.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidateList = mutableListOf<Pair<String, String>>() // (interfaceName, ip)

            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()

                // Exclude cellular data and virtual tunnel/dummy interfaces
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp") ||
                    name.contains("tun") || name.contains("ppp") || name.contains("dummy") ||
                    name.startsWith("v4-") || name.startsWith("set-") || name.startsWith("epdg")) {
                    continue
                }

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            candidateList.add(Pair(name, host))
                        }
                    }
                }
            }

            // 1. Exact default Android SoftAP address (192.168.43.1)
            val exactDefault = candidateList.find { it.second == "192.168.43.1" }
            if (exactDefault != null) return exactDefault.second

            // 2. Dedicated Hotspot/AP interfaces (ap0, ap1, swlan0, softap, etc.)
            val apCandidate = candidateList.find {
                it.first.startsWith("ap") || it.first.startsWith("swlan") || it.first.contains("softap")
            }
            if (apCandidate != null) return apCandidate.second

            // 3. Wi-Fi interfaces (wlan0, wlan1) with standard 192.168.*.*
            val wlan192 = candidateList.find {
                it.first.startsWith("wlan") && it.second.startsWith("192.168.")
            }
            if (wlan192 != null) return wlan192.second

            // 4. Any wlan interface
            val wlanAny = candidateList.find { it.first.startsWith("wlan") }
            if (wlanAny != null) return wlanAny.second

            // 5. USB tethering (rndis)
            val rndis = candidateList.find { it.first.startsWith("rndis") }
            if (rndis != null) return rndis.second

            // 6. Any other non-cellular interface with private IP
            val privateCandidate = candidateList.find {
                it.second.startsWith("192.168.") || it.second.startsWith("10.") || it.second.startsWith("172.")
            }
            if (privateCandidate != null) return privateCandidate.second

            if (candidateList.isNotEmpty()) {
                return candidateList.first().second
            }
        } catch (_: Exception) {}
        return "192.168.43.1" // Standard default Android SoftAP IP
    }
}
