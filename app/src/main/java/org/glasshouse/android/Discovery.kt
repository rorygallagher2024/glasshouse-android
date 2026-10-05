package org.glasshouse.android

import android.content.Context
import android.net.ConnectivityManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds Glasshouse TVs on the phone's network. LG TVs answer SSDP, the search
 * casting apps use, and name webOS in their reply; each one found is asked on
 * the server's port whether it runs Glasshouse. A sweep of the phone's subnet
 * catches a TV that does not answer SSDP. All of it blocks: call it off the
 * main thread.
 */
object Discovery {

    /** A Glasshouse TV: its link, with a saved token that it accepts if any. */
    data class Found(val link: TvLink, val identity: TvProbe.Identity?)

    private val SSDP_GROUP: InetAddress = InetAddress.getByName("239.255.255.250")
    private const val SSDP_PORT = 1900
    private const val SSDP_LISTEN_MS = 3000

    /** LG answers the DIAL search; its second-screen service is LG's own. */
    private val SEARCH_TARGETS = listOf(
        "urn:dial-multiscreen-org:service:dial:1",
        "urn:lge-com:service:webos-second-screen:1",
    )

    /** Short: a TV on the LAN answers in milliseconds, and the sweep asks hundreds. */
    private const val PROBE_TIMEOUT_MS = 1200
    private const val SWEEP_THREADS = 48

    /**
     * Every Glasshouse TV that answers. [tokens] are the saved ones, tried in
     * turn on a TV that wants one. [sweep] adds every address on the phone's
     * subnet (its /24 at most) to what SSDP turns up.
     */
    fun find(context: Context, tokens: Collection<String>, sweep: Boolean): List<Found> {
        val hosts = linkedSetOf<String>()
        hosts += ssdpWebOsHosts()
        if (sweep) hosts += subnetHosts(context)
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(SWEEP_THREADS, hosts.size))
        return try {
            pool.invokeAll(hosts.map { host -> Callable { glasshouseAt(host, tokens) } })
                .mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    /** Addresses whose SSDP reply names webOS in its SERVER header. */
    private fun ssdpWebOsHosts(): Set<String> {
        val hosts = mutableSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                for (target in SEARCH_TARGETS) {
                    val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n"
                    val bytes = search.toByteArray()
                    socket.send(DatagramPacket(bytes, bytes.size, SSDP_GROUP, SSDP_PORT))
                }
                val buffer = ByteArray(2048)
                val end = System.currentTimeMillis() + SSDP_LISTEN_MS
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val reply = String(packet.data, 0, packet.length)
                    if (isWebOsReply(reply)) packet.address.hostAddress?.let { hosts += it }
                }
            }
        } catch (e: IOException) {
            // No multicast on this network: the sweep, if asked for, still runs.
        }
        return hosts
    }

    /** "WebOS/4.1.0 UPnP/1.0", "LGE WebOS TV/Version 0.9" and the like. */
    fun isWebOsReply(reply: String): Boolean = reply.lineSequence().any { line ->
        line.startsWith("SERVER:", ignoreCase = true) && line.contains("webos", ignoreCase = true)
    }

    /** Every other host address on the phone's IPv4 subnet, capped at its /24. */
    private fun subnetHosts(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val la = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address } ?: return emptyList()
        return hostsAround(la.address.address, la.prefixLength).map { addr ->
            InetAddress.getByAddress(addr).hostAddress.orEmpty()
        }.filter { it.isNotEmpty() }
    }

    /**
     * The usable addresses of [own]'s subnet, other than [own] itself. A
     * prefix shorter than /24 is narrowed to [own]'s /24, which keeps a sweep
     * to a few seconds on a large network.
     */
    fun hostsAround(own: ByteArray, prefixLength: Int): List<ByteArray> {
        val prefix = prefixLength.coerceIn(24, 30)
        val ip = own.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val hostBits = 32 - prefix
        val network = ip and (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        val last = network or ((1L shl hostBits) - 1)
        return (network + 1 until last).filter { it != ip }.map { a ->
            ByteArray(4) { i -> (a shr (24 - 8 * i)).toByte() }
        }
    }

    /** The TV at [host] if Glasshouse answers there, with a token it takes. */
    private fun glasshouseAt(host: String, tokens: Collection<String>): Found? {
        val origin = "http://$host:${TvLink.DEFAULT_PORT}"
        val open = TvLink(origin, "")
        val accepted = TvProbe.tokenAccepted(open, PROBE_TIMEOUT_MS) ?: return null
        val link = if (accepted) {
            open
        } else {
            tokens.asSequence().filter { it.isNotEmpty() }.distinct()
                .map { TvLink(origin, it) }
                .firstOrNull { TvProbe.tokenAccepted(it, PROBE_TIMEOUT_MS) == true }
                ?: open
        }
        // Without an accepted token the TV keeps its MAC and name to itself.
        return Found(link, if (link.token.isEmpty() && !accepted) null else TvProbe.identify(link))
    }
}
