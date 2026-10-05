package org.glasshouse.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {

    @Test fun normalisesCaseAndDashes() =
        assertEquals("20:28:bc:52:ce:08", WakeOnLan.normaliseMac("20-28-BC-52-CE-08"))

    @Test fun rejectsNonMacs() {
        assertNull(WakeOnLan.normaliseMac(""))
        assertNull(WakeOnLan.normaliseMac("20:28:bc:52:ce"))
        assertNull(WakeOnLan.normaliseMac("00:00:00:00:00:00"))
        assertNull(WakeOnLan.normaliseMac(null))
    }

    @Test fun packetIsSyncThenSixteenCopies() {
        val p = WakeOnLan.packet("20:28:bc:52:ce:08")!!
        assertEquals(102, p.size)
        for (i in 0 until 6) assertEquals(0xFF.toByte(), p[i])
        val mac = byteArrayOf(0x20, 0x28, 0xbc.toByte(), 0x52, 0xce.toByte(), 0x08)
        for (copy in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + copy * 6, 12 + copy * 6))
    }

    @Test fun broadcastOfSlash24() = assertArrayEquals(
        byteArrayOf(192.toByte(), 168.toByte(), 1, 255.toByte()),
        WakeOnLan.broadcastOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 83), 24),
    )

    @Test fun broadcastOfSlash22() = assertArrayEquals(
        byteArrayOf(10, 0, 7, 255.toByte()),
        WakeOnLan.broadcastOf(byteArrayOf(10, 0, 5, 9), 22),
    )
}
