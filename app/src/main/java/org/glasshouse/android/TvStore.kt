package org.glasshouse.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Tv(val id: String, val name: String, val link: TvLink)

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
            Tv(o.optString("id"), o.optString("name"), TvLink(origin, o.optString("token")))
        }
    }

    fun get(id: String): Tv? = all().firstOrNull { it.id == id }

    fun byOrigin(origin: String): Tv? = all().firstOrNull { it.link.origin == origin }

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
            arr.put(JSONObject()
                .put("id", tv.id)
                .put("name", tv.name)
                .put("origin", tv.link.origin)
                .put("token", tv.link.token))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "list"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
