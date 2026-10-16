package org.glasshouse.android

import android.content.Context
import org.json.JSONObject

/** How a TV answers, as the list and the tile show it. */
enum class Reach { CHECKING, ON, ASLEEP, ONLINE, OFFLINE, UNTRUSTED_CERT }

/**
 * A TV's state as shown. [label] is the server's name for a dark TV's state,
 * [showing] what is on screen while it is on, and [stale] marks one recalled
 * from the last time the app saw the TV, until it answers again.
 */
data class Shown(
    val reach: Reach,
    val label: String? = null,
    val showing: TvProbe.Showing? = null,
    val stale: Boolean = false,
)

/** The state a probe result comes to. */
fun TvProbe.Status.toShown(): Shown {
    val power = power
    return when (reach) {
        // Without stats (a wrong token, say) the power state is unknown.
        TvProbe.Reach.ANSWERS -> when {
            power == null -> Shown(Reach.ONLINE)
            power.on -> Shown(Reach.ON, showing = showing)
            else -> Shown(Reach.ASLEEP, power.label)
        }
        TvProbe.Reach.NO_ANSWER -> Shown(Reach.OFFLINE)
        TvProbe.Reach.UNTRUSTED_CERT -> Shown(Reach.UNTRUSTED_CERT)
    }
}

/**
 * Each TV's last state seen, by origin, so the list opens on it rather than
 * on Checking for every TV. Kept in app-private prefs beside the TV list.
 */
class LastSeen(context: Context) {

    private val prefs = context.getSharedPreferences("last_seen", Context.MODE_PRIVATE)

    /** Everything recalled, marked stale. */
    fun all(): Map<String, Shown> = prefs.all.mapNotNull { (origin, raw) ->
        val o = try { JSONObject(raw as? String ?: return@mapNotNull null) } catch (e: Exception) { return@mapNotNull null }
        val reach = Reach.entries.firstOrNull { it.name == o.optString("reach") } ?: return@mapNotNull null
        val s = o.optJSONObject("showing")
        val showing = s?.let {
            TvProbe.Showing(
                source = it.optString("source").ifEmpty { null },
                range = it.optString("range").ifEmpty { null },
                picture = it.optString("picture").ifEmpty { null },
                volume = if (it.has("volume")) it.optInt("volume") else null,
                muted = it.optBoolean("muted"),
            )
        }
        origin to Shown(reach, o.optString("label").ifEmpty { null }, showing, stale = true)
    }.toMap()

    fun save(origin: String, shown: Shown) {
        if (shown.reach == Reach.CHECKING) return
        val o = JSONObject().put("reach", shown.reach.name)
        shown.label?.let { o.put("label", it) }
        shown.showing?.let { s ->
            val j = JSONObject()
            s.source?.let { j.put("source", it) }
            s.range?.let { j.put("range", it) }
            s.picture?.let { j.put("picture", it) }
            s.volume?.let { j.put("volume", it) }
            j.put("muted", s.muted)
            o.put("showing", j)
        }
        prefs.edit().putString(origin, o.toString()).apply()
    }

    /** Drops what is kept for addresses no saved TV has any more. */
    fun keepOnly(origins: Set<String>) {
        val gone = prefs.all.keys - origins
        if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach { remove(it) } }.apply()
    }
}

/** Which saved TV the Quick Settings tile turns on and off. */
object TileChoice {

    private const val PREFS = "tile"
    private const val KEY_TV = "tv"

    /** The chosen TV, or the only one when there is just one. */
    fun tv(context: Context): Tv? {
        val store = TvStore(context)
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TV, null)
        return id?.let { store.get(it) } ?: store.all().singleOrNull()
    }

    fun choose(context: Context, tv: Tv) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TV, tv.id).apply()
    }
}
