package org.glasshouse.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvLinkTest {

    @Test
    fun qrCodeLinkGivesOriginAndToken() {
        val link = TvLink.parse("http://192.168.1.20:8080/?tab=privacy&k=a%2Bb%20c")!!
        assertEquals("http://192.168.1.20:8080", link.origin)
        assertEquals("a+b c", link.token)
    }

    @Test
    fun qrCodeLinkWithoutTokenHasNone() {
        val link = TvLink.parse("http://192.168.1.20:8080/")!!
        assertEquals("", link.token)
    }

    @Test
    fun bareHostGetsTheDefaultPort() {
        assertEquals("http://192.168.1.20:8080", TvLink.parse(" 192.168.1.20 ")!!.origin)
        assertEquals("http://lgtv.local:8080", TvLink.parse("lgtv.local")!!.origin)
    }

    @Test
    fun typedPortIsKept() {
        assertEquals("http://192.168.1.20:8081", TvLink.parse("192.168.1.20:8081")!!.origin)
    }

    @Test
    fun explicitSchemeKeepsItsOwnPort() {
        assertEquals("https://tv.example.lan", TvLink.parse("https://tv.example.lan/")!!.origin)
    }

    @Test
    fun ipv6HostKeepsItsBrackets() {
        assertEquals("http://[fd00::20]:8080", TvLink.parse("http://[fd00::20]:8080/?k=x")!!.origin)
    }

    @Test
    fun notADashboardAddress() {
        assertNull(TvLink.parse(""))
        assertNull(TvLink.parse("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(TvLink.parse("ftp://192.168.1.20/"))
        assertNull(TvLink.parse("http://"))
        assertNull(TvLink.parse("not an address"))
    }

    @Test
    fun dashboardUrlCarriesTheToken() {
        assertEquals("http://h:8080/", TvLink("http://h:8080", "").dashboardUrl())
        assertEquals("http://h:8080/?k=a%2Bb", TvLink("http://h:8080", "a+b").dashboardUrl())
    }

    @Test
    fun labelDropsTheScheme() {
        assertEquals("192.168.1.20:8080", TvLink("http://192.168.1.20:8080", "").label)
    }

    @Test fun hostDropsPortAndScheme() {
        assertEquals("192.168.1.20", TvLink.parse("192.168.1.20")!!.host)
        assertEquals("lgtv.local", TvLink.parse("https://lgtv.local:8443/?k=x")!!.host)
        assertEquals("fe80::1", TvLink.parse("http://[fe80::1]:8080/")!!.host)
    }
}
