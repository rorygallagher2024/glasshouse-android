package org.glasshouse.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved TV. [mac] is learned from the TV once it answers: it is what Wake-on-LAN
 * sends to, and how a TV that has moved to a new address is recognised.
 * [wakeOnLan] is the TV's own "Turn on via Wi-Fi" setting, and [model] its
 * model number, both as last seen, so an offline TV still shows them.
 */
data class Tv(
    val id: String,
    val name: String,
    val link: TvLink,
    val mac: String? = null,
    val wakeOnLan: Boolean? = null,
    val model: String? = null,
)

/** The saved TVs, in the order they were added, kept in app-private prefs. */
class TvStore(context: Context) {

    private val prefs = context.getSharedPreferences("tvs", Context.MODE_PRIVATE)

    fun all(): List<Tv> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val origin = o.optString("origin")
            if (origin.isEmpty()) return@mapNotNull null
            Tv(
                id = o.optString("id"),
                name = o.optString("name"),
                link = TvLink(origin, o.optString("token")),
                mac = o.optString("mac").ifEmpty { null },
                wakeOnLan = if (o.has("wakeOnLan")) o.optBoolean("wakeOnLan") else null,
                model = o.optString("model").ifEmpty { null },
            )
        }
    }

    fun get(id: String): Tv? = all().firstOrNull { it.id == id }

    fun byOrigin(origin: String): Tv? = all().firstOrNull { it.link.origin == origin }

    fun byMac(mac: String): Tv? = all().firstOrNull { it.mac == mac }

    /** Adds the TV, or replaces the saved one with the same id. */
    fun save(tv: Tv) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == tv.id }
        if (i >= 0) list[i] = tv else list.add(tv)
        write(list)
    }

    fun remove(id: String) = write(all().filter { it.id != id })

    private fun write(list: List<Tv>) {
        val arr = JSONArray()
        for (tv in list) {
            val o = JSONObject()
                .put("id", tv.id)
                .put("name", tv.name)
                .put("origin", tv.link.origin)
                .put("token", tv.link.token)
            tv.mac?.let { o.put("mac", it) }
            tv.wakeOnLan?.let { o.put("wakeOnLan", it) }
            tv.model?.let { o.put("model", it) }
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "list"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
