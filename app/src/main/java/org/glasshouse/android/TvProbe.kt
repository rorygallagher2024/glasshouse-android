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
 * in seconds. All of them block: call them off the main thread.
 */
object TvProbe {

    enum class Reach { ANSWERS, NO_ANSWER, UNTRUSTED_CERT }

    /**
     * What /api/stats says about the TV itself. [name] is the TV's own, such
     * as "LG C2 OLED", for naming a TV found on the network.
     */
    data class Identity(val mac: String, val wakeOnLan: Boolean?, val name: String? = null)

    /**
     * The server's power state. A TV can answer while dark: Active Standby
     * finishing panel maintenance, Always-on, Always Ready, or Screen off.
     * [label] is the server's name for the state.
     */
    data class Power(val on: Boolean, val label: String)

    data class Status(val reach: Reach, val identity: Identity?, val power: Power?)

    private const val TIMEOUT_MS = 4000

    /** powerOn waits on the TV before it replies. */
    private const val CONTROL_TIMEOUT_MS = 15_000

    /** Any HTTP answer counts, even a 401 for a wrong token. */
    fun check(link: TvLink): Reach {
        val conn = open("${link.origin}/api/caps", TIMEOUT_MS) ?: return Reach.NO_ANSWER
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

    /** Whether the TV answers and, when it does, what it says about itself. */
    fun status(link: TvLink): Status {
        val reach = check(link)
        if (reach != Reach.ANSWERS) return Status(reach, null, null)
        val stats = stats(link)
        return Status(reach, stats?.let(::identityOf), stats?.let(::powerOf))
    }

    /**
     * The MAC address of the interface the TV is using, and its Wake-on-LAN
     * setting. Null when the TV does not answer, the token is wrong, or the
     * server is too old to report a MAC.
     */
    fun identify(link: TvLink): Identity? = stats(link)?.let(::identityOf)

    /**
     * Asks the server to bring the TV out of standby: its own powerOn, which
     * covers Active Standby, Always-on and Screen off. Null on success, or the
     * server's reason, such as power actions being off in its config.
     */
    fun powerOn(link: TvLink): String? {
        val conn = open("${link.origin}/api/control${tokenQuery(link)}", CONTROL_TIMEOUT_MS) ?: return ""
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            // The server takes JSON only, and refuses a foreign Origin; none is sent.
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write("""{"action":"powerOn"}""".toByteArray()) }
            val code = conn.responseCode
            val body = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val reply = try { JSONObject(body) } catch (e: JSONException) { null }
            if (code == 200 && reply?.optBoolean("ok") == true) null else reply?.optString("error").orEmpty()
        } catch (e: IOException) {
            ""
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Whether a Glasshouse server answers at [link] with its token: true for
     * a 200 from /api/caps, false for its 401, null for anything else (no
     * answer, or not Glasshouse). The 401 still names it: "bad or missing token".
     */
    fun tokenAccepted(link: TvLink, timeoutMs: Int): Boolean? {
        val conn = open("${link.origin}/api/caps${tokenQuery(link)}", timeoutMs) ?: return null
        return try {
            when (conn.responseCode) {
                200 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optBoolean("ok")
                    .takeIf { it }
                401 -> conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    .takeIf { it.contains("token") }?.let { false }
                else -> null
            }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun stats(link: TvLink): JSONObject? {
        val conn = open("${link.origin}/api/stats${tokenQuery(link)}", TIMEOUT_MS) ?: return null
        return try {
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun identityOf(stats: JSONObject): Identity? {
        val mac = WakeOnLan.normaliseMac(stats.optString("mac")) ?: return null
        val name = stats.optJSONObject("device")?.optString("name").orEmpty().ifEmpty { null }
        return Identity(mac, if (stats.has("wakeOnLan")) stats.optBoolean("wakeOnLan") else null, name)
    }

    private fun powerOf(stats: JSONObject): Power? {
        val p = stats.optJSONObject("powerState") ?: return null
        val on = p.optBoolean("systemOn") && p.optBoolean("screenOn")
        return Power(on, p.optString("label"))
    }

    private fun tokenQuery(link: TvLink) =
        if (link.token.isEmpty()) "" else "?k=" + URLEncoder.encode(link.token, "UTF-8")

    private fun open(url: String, timeoutMs: Int): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
        }
    } catch (e: IOException) {
        null
    }
}
