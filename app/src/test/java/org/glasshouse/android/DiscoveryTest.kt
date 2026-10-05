package org.glasshouse.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    private fun ip(vararg b: Int) = ByteArray(4) { b[it].toByte() }

    @Test fun lgRepliesAreWebOs() {
        assertTrue(Discovery.isWebOsReply("HTTP/1.1 200 OK\r\nSERVER: WebOS/4.1.0 UPnP/1.0\r\nST: upnp:rootdevice\r\n"))
        assertTrue(Discovery.isWebOsReply("HTTP/1.1 200 OK\r\nServer: Linux/i686 UPnP/1,0 DLNADOC/1.50 LGE WebOS TV/Version 0.9\r\n"))
    }

    @Test fun otherDevicesAreNot() {
        assertFalse(Discovery.isWebOsReply("HTTP/1.1 200 OK\r\nSERVER: Samsung-Linux/4.1, UPnP/1.0, Samsung_UPnP_SDK/1.0\r\n"))
        assertFalse(Discovery.isWebOsReply("HTTP/1.1 200 OK\r\nSERVER: Linux UPnP/1.0 Sonos/97.1-80312 (ZPS41)\r\n"))
        // webOS elsewhere in the reply, not as the server, does not count.
        assertFalse(Discovery.isWebOsReply("HTTP/1.1 200 OK\r\nSERVER: Chromecast/1.6\r\nX-NOTE: webos\r\n"))
    }

    @Test fun slash24LeavesOutNetworkBroadcastAndOwn() {
        val hosts = Discovery.hostsAround(ip(192, 168, 1, 83), 24)
        assertEquals(253, hosts.size)
        assertArrayEquals(ip(192, 168, 1, 1), hosts.first())
        assertArrayEquals(ip(192, 168, 1, 254), hosts.last())
        assertTrue(hosts.none { it.contentEquals(ip(192, 168, 1, 83)) })
    }

    @Test fun widerSubnetIsNarrowedToOwn24() {
        val hosts = Discovery.hostsAround(ip(10, 0, 5, 9), 16)
        assertEquals(253, hosts.size)
        assertArrayEquals(ip(10, 0, 5, 1), hosts.first())
    }

    @Test fun smallSubnetStaysSmall() {
        val hosts = Discovery.hostsAround(ip(192, 168, 1, 66), 29)
        assertEquals(5, hosts.size)
        assertArrayEquals(ip(192, 168, 1, 65), hosts.first())
        assertArrayEquals(ip(192, 168, 1, 70), hosts.last())
    }
}
