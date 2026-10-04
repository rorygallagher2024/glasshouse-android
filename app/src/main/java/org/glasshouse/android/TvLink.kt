package org.glasshouse.android

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A dashboard address: what a TV's QR code or a typed address comes down to.
 * Plain JVM code, so the parsing is unit-tested without a device.
 */
data class TvLink(val origin: String, val token: String) {

    /** The host and port as shown in the TV list, without the scheme. */
    val label: String get() = origin.substringAfter("://")

    /**
     * The dashboard page. The token goes in `k`, which the dashboard keeps in
     * its own storage and takes back out of the address bar.
     */
    fun dashboardUrl(): String =
        if (token.isEmpty()) "$origin/" else "$origin/?k=" + URLEncoder.encode(token, "UTF-8")

    companion object {
        /** The server's default port; config.json's `port`. */
        const val DEFAULT_PORT = 8080

        /**
         * Parses a scanned QR code or a typed address. The TV's QR codes are
         * full links such as `http://192.168.1.20:8080/?tab=privacy&k=…`; a
         * typed address may be a bare host, which gets the server's default
         * port. A link that names its scheme keeps that scheme's port, since
         * it may be behind a proxy. Returns null for anything that is not an
         * http(s) address.
         */
        fun parse(input: String): TvLink? {
            val text = input.trim()
            if (text.isEmpty()) return null
            val schemeGiven = text.contains("://")
            val uri = try {
                URI(if (schemeGiven) text else "http://$text")
            } catch (e: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            if (host.isEmpty()) return null
            val port = when {
                uri.port != -1 -> uri.port
                schemeGiven -> -1
                else -> DEFAULT_PORT
            }
            val origin = buildString {
                append(scheme).append("://").append(host)
                if (port != -1) append(':').append(port)
            }
            return TvLink(origin, queryParam(uri.rawQuery, "k") ?: "")
        }

        private fun queryParam(rawQuery: String?, name: String): String? {
            if (rawQuery.isNullOrEmpty()) return null
            for (pair in rawQuery.split('&')) {
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                if (key == name) {
                    return if (eq < 0) "" else URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                }
            }
            return null
        }
    }
}
