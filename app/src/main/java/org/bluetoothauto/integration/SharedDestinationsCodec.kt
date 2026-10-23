// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Bluetooth places remain authoritative; the sidecar only preserves richer projection fields. */
object SharedDestinationsCodec {
    private const val MAX_LENGTH = 120_000
    data class HostRows(val saved: String, val recent: String, val details: String)

    fun toProjection(saved: String, recent: String, details: String?): String {
        val previous = runCatching {
            require(details != null && details.length <= MAX_LENGTH)
            JSONObject(details).takeIf { it.optInt("version") == 1 }
        }.getOrNull()
        fun convert(raw: String, key: String, maximum: Int): JSONArray {
            val metadata = mutableMapOf<String, JSONObject>()
            previous?.optJSONArray(key)?.let { array ->
                for (i in 0 until minOf(array.length(), maximum)) {
                    val item = array.optJSONObject(i) ?: continue
                    coordinate(item.optDouble("latitude"), item.optDouble("longitude"))?.let { metadata[it] = item }
                }
            }
            val result = JSONArray()
            val seen = hashSetOf<String>()
            val shortcuts = hashSetOf<String>()
            if (raw.length > MAX_LENGTH) return result
            val rows = runCatching { JSONArray(raw) }.getOrNull() ?: return result
            for (i in 0 until minOf(rows.length(), maximum)) {
                val item = rows.optJSONObject(i) ?: continue
                val latitude = item.optDouble("lat")
                val longitude = item.optDouble("lon")
                val pin = coordinate(latitude, longitude) ?: continue
                val name = item.optString("name").trim()
                if (!validName(name) || !seen.add(pin)) continue
                val old = metadata[pin]
                val id = old?.optString("id")?.takeIf(::validId)
                    ?: UUID.nameUUIDFromBytes("libreauto:$key:$pin".toByteArray(Charsets.UTF_8)).toString()
                val address = old?.optString("address").orEmpty().takeIf(::validAddress).orEmpty()
                var shortcut = old?.optString("shortcut")?.takeIf { it in setOf("HOME", "WORK") } ?: "NONE"
                if (key != "saved" || (shortcut != "NONE" && !shortcuts.add(shortcut))) shortcut = "NONE"
                result.put(JSONObject().put("id", id).put("name", name).put("address", address)
                    .put("latitude", latitude).put("longitude", longitude).put("shortcut", shortcut))
            }
            return result
        }
        return JSONObject().put("version", 1).put("saved", convert(saved, "saved", 50))
            .put("recent", convert(recent, "recent", 20)).toString()
    }

    /** Validate the entire replacement before the backend makes one atomic preference edit. */
    fun toHost(projection: String): HostRows {
        require(projection.length <= MAX_LENGTH) { "Saved places are too large" }
        val root = JSONObject(projection)
        require(root.getInt("version") == 1) { "Unsupported saved places format" }
        fun convert(key: String, maximum: Int): String {
            val array = root.getJSONArray(key)
            require(array.length() <= maximum) { "Too many saved places" }
            val result = JSONArray()
            val ids = hashSetOf<String>()
            val coordinates = hashSetOf<String>()
            val shortcuts = hashSetOf<String>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val latitude = item.getDouble("latitude")
                val longitude = item.getDouble("longitude")
                val pin = coordinate(latitude, longitude)
                val name = item.getString("name")
                val id = item.getString("id")
                val shortcut = item.getString("shortcut")
                require(pin != null && coordinates.add(pin) && validName(name) && validId(id) && ids.add(id)) { "Invalid saved place" }
                require(validAddress(item.optString("address"))) { "Invalid saved address" }
                require(shortcut in setOf("NONE", "HOME", "WORK") && (key == "saved" || shortcut == "NONE")) { "Invalid place shortcut" }
                require(shortcut == "NONE" || shortcuts.add(shortcut)) { "Repeated place shortcut" }
                result.put(JSONObject().put("name", name).put("lat", latitude).put("lon", longitude))
            }
            return result.toString()
        }
        return HostRows(convert("saved", 50), convert("recent", 20), projection)
    }

    private fun coordinate(latitude: Double, longitude: Double): String? =
        if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
            "${Math.round(latitude * 100000)}:${Math.round(longitude * 100000)}" else null
    private fun validId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun validName(value: String) = value.isNotBlank() && value.length <= 200 && value.none(Char::isISOControl)
    private fun validAddress(value: String) = value.length <= 300 && value.none(Char::isISOControl)
}
