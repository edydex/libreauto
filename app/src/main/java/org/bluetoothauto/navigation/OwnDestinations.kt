// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Only explicitly selected destinations are saved; never the user's travelled path. */
object OwnDestinations {
    private const val PREF = "own-map-destinations"
    fun saved(context: Context): List<OwnMapProvider.Place> = read(context, "saved", 50)
    fun recent(context: Context): List<OwnMapProvider.Place> = read(context, "recent", 20)
    fun save(context: Context, place: OwnMapProvider.Place) {
        val current = saved(context)
        if (current.size >= 50 && current.none { same(it, place) }) throw IllegalStateException("Saved places are full. Remove one first.")
        write(context, "saved", listOf(place) + current.filterNot { same(it, place) })
    }
    fun remove(context: Context, place: OwnMapProvider.Place) { write(context, "saved", saved(context).filterNot { same(it, place) }) }
    fun recordRecent(context: Context, place: OwnMapProvider.Place) {
        write(context, "recent", (listOf(place) + recent(context).filterNot { same(it, place) }).take(20))
    }
    private fun same(a: OwnMapProvider.Place, b: OwnMapProvider.Place) = a.point.distanceTo(b.point) < 1.0
    private fun read(context: Context, key: String, limit: Int): List<OwnMapProvider.Place> = try {
        val raw = context.getSharedPreferences(PREF, 0).getString(key, "[]") ?: "[]"
        if (raw.length > 80_000) emptyList() else {
            val rows = JSONArray(raw)
            (0 until rows.length().coerceAtMost(limit)).mapNotNull { i -> try {
                val item = rows.getJSONObject(i)
                OwnMapProvider.Place(item.getString("name"), GeoPoint(item.getDouble("lat"), item.getDouble("lon")))
            } catch (_: Exception) { null } }
        }
    } catch (_: Exception) { emptyList() }
    private fun write(context: Context, key: String, places: List<OwnMapProvider.Place>) {
        val rows = JSONArray()
        places.forEach { rows.put(JSONObject().put("name", it.name).put("lat", it.point.latitude).put("lon", it.point.longitude)) }
        context.getSharedPreferences(PREF, 0).edit().putString(key, rows.toString()).apply()
    }
}
