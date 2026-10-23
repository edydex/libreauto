// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.content.Context
import org.bluetoothauto.model.Track
import org.bluetoothauto.navigation.HintPlacement
import org.json.JSONArray
import org.json.JSONObject

object Library {
    private const val PREF = "bluetoothauto"
    private var cachedTracksRaw: String? = null
    private var cachedTracks: List<Track>? = null
    @Synchronized fun tracks(context: Context): List<Track> {
        val raw = context.getSharedPreferences(PREF,0).getString("tracks","[]") ?: "[]"
        if (cachedTracksRaw == raw) cachedTracks?.let { return it }
        val parsed = try {
            val a = JSONArray(raw)
            (0 until a.length().coerceAtMost(512)).map { i -> a.getJSONObject(i).let {
                Track(it.getString("uri"),it.optString("title","Music"),it.optString("artist",""))
            } }.filter { it.uri.startsWith("content://") || it.uri.startsWith("file://") }
        } catch (_: Exception) { emptyList() }
        cachedTracksRaw = raw
        cachedTracks = parsed
        return parsed
    }
    @Synchronized fun save(context: Context, tracks: List<Track>) {
        val a = JSONArray()
        tracks.take(512).forEach {
            a.put(JSONObject().put("uri",it.uri).put("title",it.title).put("artist",it.artist))
        }
        context.getSharedPreferences(PREF,0).edit().putString("tracks",a.toString()).apply()
        cachedTracksRaw = null
        cachedTracks = null
    }
    fun shuffle(context: Context) = context.getSharedPreferences(PREF,0).getBoolean("shuffle",false)
    fun shuffle(context: Context, enabled: Boolean) { context.getSharedPreferences(PREF,0).edit().putBoolean("shuffle",enabled).apply() }
    fun musicGrants(context: Context): List<String> = try {
        val values=JSONArray(context.getSharedPreferences(PREF,0).getString("musicGrants","[]"))
        (0 until values.length().coerceAtMost(1024)).map { values.getString(it) }.filter { it.startsWith("content://") }
    } catch (_: Exception) { emptyList() }
    fun musicGrants(context: Context, uris: List<String>) {
        val values=JSONArray();uris.distinct().take(1024).forEach { values.put(it) }
        context.getSharedPreferences(PREF,0).edit().putString("musicGrants",values.toString()).apply()
    }
    fun units(context: Context): String = context.getSharedPreferences(PREF,0).getString("units","AUTO") ?: "AUTO"
    fun units(context: Context, value: String) { context.getSharedPreferences(PREF,0).edit().putString("units",value).apply() }
    fun mapEnabled(context: Context) = context.getSharedPreferences(PREF,0).getBoolean("map",true)
    fun mapEnabled(context: Context, value: Boolean) { context.getSharedPreferences(PREF,0).edit().putBoolean("map",value).apply() }
    fun hintPlacement(context: Context): HintPlacement = HintPlacement.fromPreference(context.getSharedPreferences(PREF,0).getString("hintPlacement",null))
    fun hintPlacement(context: Context, value: HintPlacement) { context.getSharedPreferences(PREF,0).edit().putString("hintPlacement",value.name).apply() }
    fun navigator(context: Context): String = context.getSharedPreferences(PREF,0).getString("navigator","GOOGLE")
        ?.takeIf { it == "OPEN" } ?: "GOOGLE"
    fun navigator(context: Context, value: String) { context.getSharedPreferences(PREF,0).edit().putString("navigator",if(value=="OPEN")"OPEN" else "GOOGLE").apply() }
    fun audioMode(context: Context):String = context.getSharedPreferences(PREF,0).getString("audioMode","OFF")?.takeIf{it in listOf("OFF","DIRECTIONS","MESSAGES","BOTH")} ?: "OFF"
    fun audioMode(context: Context, value: String) { context.getSharedPreferences(PREF,0).edit().putString("audioMode",value.takeIf{it in listOf("DIRECTIONS","MESSAGES","BOTH")} ?: "OFF").apply() }
    fun speakDirections(context:Context)=audioMode(context) in listOf("DIRECTIONS","BOTH")
    fun speakMessages(context:Context)=audioMode(context) in listOf("MESSAGES","BOTH")
}
