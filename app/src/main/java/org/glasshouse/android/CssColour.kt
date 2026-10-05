package org.glasshouse.android

/** Reads what getComputedStyle gives for a colour. Plain JVM code, so unit-tested. */
object CssColour {

    /**
     * An opaque `rgb(r, g, b)` or `rgba(r, g, b, 1)` as an Android colour int.
     * Null for anything translucent, which is not what the page shows behind it.
     */
    fun parse(css: String): Int? {
        val text = css.trim()
        if (!text.startsWith("rgb")) return null
        val parts = text.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
        if (parts.size !in 3..4) return null
        val channels = parts.take(3).map { part ->
            val v = part.toFloatOrNull() ?: return null
            if (v < 0f || v > 255f) return null
            v.toInt()
        }
        val alpha = if (parts.size == 4) parts[3].toFloatOrNull() ?: return null else 1f
        if (alpha < 1f) return null
        return (0xFF shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }
}
