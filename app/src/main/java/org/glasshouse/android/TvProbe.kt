package org.glasshouse.android

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether a TV's server answers at all. A TV that is off or asleep drops
 * packets rather than refusing them, so a WebView pointed at it waits out the
 * system's TCP timeout (minutes) on a blank page; this gives up in seconds.
 */
object TvProbe {

    private const val TIMEOUT_MS = 4000

    /** Blocks; call off the main thread. Any HTTP answer counts, even a 401. */
    fun answers(link: TvLink): Boolean {
        val conn = try {
            URL("${link.origin}/api/caps").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return false
        }
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.responseCode
            true
        } catch (e: IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
