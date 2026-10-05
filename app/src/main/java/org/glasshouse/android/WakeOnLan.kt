package org.glasshouse.android

import android.content.Context
import android.net.ConnectivityManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Turns a TV on from standby with a magic packet. The TV only listens for one
 * with its "Turn on via Wi-Fi" setting on ("Mobile TV On" on older models).
 */
object WakeOnLan {

    /** Lower-case colon form, or null for anything that is not a MAC. */
    fun normaliseMac(text: String?): String? {
        val hex = text?.trim()?.lowercase()?.replace("-", ":") ?: return null
        if (!Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$").matches(hex)) return null
        if (hex == "00:00:00:00:00:00") return null
        return hex
    }

    /** Six 0xFF bytes, then the MAC sixteen times. Null for a bad MAC. */
    fun packet(mac: String): ByteArray? {
        val bytes = normaliseMac(mac)?.split(':')?.map { it.toInt(16).toByte() } ?: return null
        return ByteArray(6) { 0xFF.toByte() } + List(16) { bytes }.flatten().toByteArray()
    }

    /**
     * IPv4 broadcast address of a network, from one of its addresses and the
     * prefix length.
     */
    fun broadcastOf(address: ByteArray, prefixLength: Int): ByteArray {
        val ip = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val hostMask = (1L shl (32 - prefixLength)) - 1
        val broadcast = ip or hostMask
        return ByteArray(4) { i -> (broadcast shr (24 - 8 * i)).toByte() }
    }

    /**
     * Sends the packet to the phone's own network broadcast address, the
     * all-ones broadcast and the TV's last address, on ports 9 and 7. Some
     * routers drop one or other of the broadcasts; a TV in standby can still
     * answer at its address while its entry is in the router's ARP cache.
     * Blocks; call off the main thread.
     */
    fun send(context: Context, mac: String, lastHost: String?) {
        val payload = packet(mac) ?: return
        val targets = mutableSetOf<InetAddress>()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.forEach { la ->
            val addr = la.address
            if (addr is Inet4Address) targets += InetAddress.getByAddress(broadcastOf(addr.address, la.prefixLength))
        }
        targets += InetAddress.getByName("255.255.255.255")
        lastHost?.let { host ->
            try {
                targets += InetAddress.getByName(host)
            } catch (e: IOException) {
                // A name that no longer resolves; the broadcasts still go.
            }
        }
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (target in targets) {
                    for (port in intArrayOf(9, 7)) {
                        try {
                            socket.send(DatagramPacket(payload, payload.size, target, port))
                        } catch (e: IOException) {
                            // One unreachable target does not stop the rest.
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // No socket at all: nothing more to try.
        }
    }
}
