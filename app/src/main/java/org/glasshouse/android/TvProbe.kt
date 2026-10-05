package org.glasshouse.android

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.SSLException

/**
 * Asks a TV's server whether it is there, and what it is. A TV that is off or
 * asleep drops packets rather than refusing them, so a WebView pointed at it
 * waits out the system's TCP timeout (minutes) on a blank page; these give up
 * in seconds. Both block: call them off the main thread.
 */
object TvProbe {

    enum class Reach { ANSWERS, NO_ANSWER, UNTRUSTED_CERT }

    /** What /api/stats says about the TV itself. */
    data class Identity(val mac: String, val wakeOnLan: Boolean?)

    private const val TIMEOUT_MS = 4000

    /** Any HTTP answer counts, even a 401 for a wrong token. */
    fun check(link: TvLink): Reach {
        val conn = open("${link.origin}/api/caps") ?: return Reach.NO_ANSWER
        return try {
            conn.responseCode
            Reach.ANSWERS
        } catch (e: SSLException) {
            // A self-signed certificate behind a reverse proxy, typically.
            Reach.UNTRUSTED_CERT
        } catch (e: IOException) {
            Reach.NO_ANSWER
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The MAC address of the interface the TV is using, and its Wake-on-LAN
     * setting. Null when the TV does not answer, the token is wrong, or the
     * server is too old to report a MAC.
     */
    fun identify(link: TvLink): Identity? {
        val query = if (link.token.isEmpty()) "" else "?k=" + URLEncoder.encode(link.token, "UTF-8")
        val conn = open("${link.origin}/api/stats$query") ?: return null
        return try {
            if (conn.responseCode != 200) return null
            val stats = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val mac = WakeOnLan.normaliseMac(stats.optString("mac")) ?: return null
            Identity(mac, if (stats.has("wakeOnLan")) stats.optBoolean("wakeOnLan") else null)
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
        }
    } catch (e: IOException) {
        null
    }
}
