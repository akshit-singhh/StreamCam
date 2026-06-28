package com.streamcam.utils

import android.content.Context
import android.net.wifi.WifiManager
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Returns the device's local IP address on the current network.
     * Tries WiFi first (most common for local streaming), falls back to
     * any active non-loopback interface.
     */
    fun getLocalIpAddress(context: Context): String {
        // Try WifiManager first — reliable for WiFi
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ip and 0xFF,
                    ip shr 8 and 0xFF,
                    ip shr 16 and 0xFF,
                    ip shr 24 and 0xFF
                )
            }
        } catch (_: Exception) {}

        // Fallback: enumerate network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}

        return "0.0.0.0"
    }
}
