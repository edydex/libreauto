// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import android.content.Context
import org.bluetoothauto.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/** Strict, versioned serialization is also used by JVM tests and the one-time queue migration. */
object MusicLibraryCodec {
    const val VERSION = 1
    const val MAX_JSON_LENGTH = 4 * 1024 * 1024

    fun encode(library: MusicLibrary): String {
        library.validate()
        // Bound allocation before building JSON, including worst-case escaping of control characters.
        var encodedLength = 100L
        fun count(value: String) {
            encodedLength += 2 + value.sumOf { character ->
                when (character) {
                    '"', '\\', '\b', '\u000c', '\n', '\r', '\t' -> 2L
                    else -> if (character < ' ') 6L else 1L
                }
            }
            if (encodedLength > MAX_JSON_LENGTH) throw MusicLibraryException("This library is too large to save. Remove a few items and try again.")
        }
        library.playlists.forEach { playlist ->
            encodedLength += 100
            count(playlist.id); count(playlist.name)
            playlist.tracks.forEach { track ->
                encodedLength += 100
                count(track.uri); count(track.title); count(track.artist)
            }
        }
        library.folders.forEach { folder ->
            encodedLength += 100
            count(folder.id); count(folder.name); count(folder.treeUri)
        }
        val playlists = JSONArray()
        library.playlists.forEach { playlist ->
            playlists.put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("tracks", encodeTracks(playlist.tracks)))
        }
        val folders = JSONArray()
        library.folders.forEach { folder ->
            folders.put(JSONObject().put("id", folder.id).put("name", folder.name).put("treeUri", folder.treeUri))
        }
        return JSONObject().put("version", VERSION).put("playlists", playlists).put("folders", folders).toString().also {
            if (it.length > MAX_JSON_LENGTH) throw MusicLibraryException("This library is too large to save. Remove a few items and try again.")
        }
    }

    fun decode(raw: String): MusicLibrary = parseSafely {
        checkLength(raw)
        val root = JSONObject(raw)
        if (root.get("version") != VERSION) throw MusicLibraryException("This saved library needs a different version of BluetoothAuto. Your data has been kept.")
        val playlistValues = root.getJSONArray("playlists")
        val folderValues = root.getJSONArray("folders")
        if (playlistValues.length() > MusicLibrary.MAX_PLAYLISTS || folderValues.length() > MusicLibrary.MAX_FOLDERS) {
            throw MusicLibraryException("The saved library is larger than this version supports. Your data has been kept.")
        }
        MusicLibrary(
            playlists = (0 until playlistValues.length()).map { index ->
                val value = playlistValues.getJSONObject(index)
                MusicPlaylist(value.getString("id"), value.getString("name"), decodeTracks(value.getJSONArray("tracks")))
            },
            folders = (0 until folderValues.length()).map { index ->
                val value = folderValues.getJSONObject(index)
                MusicFolder(value.getString("id"), value.getString("name"), value.getString("treeUri"))
            }
        ).validate()
    }

    /** The old queue remains untouched, including order and duplicate entries. */
    fun migrateLegacy(rawQueue: String, rawGrants: String = "[]"): MusicLibrary = parseSafely {
        checkLength(rawQueue)
        checkLength(rawGrants)
        val tracks = decodeTracks(JSONArray(rawQueue))
        var library = if (tracks.isEmpty()) MusicLibrary() else MusicLibrary().createPlaylist("Your music", tracks, "legacy-your-music")
        val grants = JSONArray(rawGrants)
        if (grants.length() > 1024) throw MusicLibraryException("The previous music folder list is too large to migrate safely. Your data has been kept.")
        val roots = (0 until grants.length()).map { grants.getString(it) }
            .filter { it.startsWith("content://") && it.contains("/tree/") }.distinct()
        roots.forEach { uri ->
            val label = runCatching { URLDecoder.decode(uri.substringAfter("/tree/").substringBefore("/document/").replace("+", "%2B"), "UTF-8") }
                .getOrDefault("").substringAfterLast(':').substringAfterLast('/')
                .map { if (it == '\r' || it == '\n' || it == '\u0000') ' ' else it }.joinToString("")
                .trim().take(MusicLibrary.MAX_NAME_LENGTH).ifBlank { "Music folder" }
            library = library.addFolder(label, uri, "legacy-folder-${UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8))}")
        }
        library
    }

    private fun encodeTracks(tracks: List<Track>): JSONArray = JSONArray().apply {
        tracks.forEach { put(JSONObject().put("uri", it.uri).put("title", it.title).put("artist", it.artist)) }
    }
    private fun decodeTracks(values: JSONArray): List<Track> {
        if (values.length() > MusicLibrary.MAX_TRACKS) throw MusicLibraryException("A saved playlist contains too many tracks. Your data has been kept.")
        return (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            Track(value.getString("uri"), if (value.has("title")) value.getString("title") else "Music",
                if (value.has("artist")) value.getString("artist") else "")
        }
    }
    private fun checkLength(raw: String) {
        if (raw.length > MAX_JSON_LENGTH) throw MusicLibraryException("The saved library is too large to open safely. Your data has been kept.")
    }
    private inline fun <T> parseSafely(work: () -> T): T = try {
        work()
    } catch (error: MusicLibraryException) {
        throw error
    } catch (error: Exception) {
        throw MusicLibraryException("The saved music library could not be read. Your original data has been kept.", error)
    }
}

/** Shares a preference file with the existing queue, but never changes its tracks, shuffle, or URI grants. */
object MusicLibraryStore {
    private const val PREF = "bluetoothauto"
    private const val KEY = "musicLibraryV1"
    private var cachedRaw: String? = null
    private var cachedLibrary: MusicLibrary? = null

    @Synchronized fun load(context: Context): MusicLibrary {
        val preferences = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = preferences.getString(KEY, null)
        if (raw == null) {
            val migrated = MusicLibraryCodec.migrateLegacy(preferences.getString("tracks", "[]") ?: "[]", preferences.getString("musicGrants", "[]") ?: "[]")
            persist(context, migrated)
            return migrated
        }
        if (cachedRaw == raw) cachedLibrary?.let { return it }
        return MusicLibraryCodec.decode(raw).also {
            cachedRaw = raw
            cachedLibrary = it
        }
    }

    @Synchronized fun save(context: Context, library: MusicLibrary) {
        // A failed read must not be followed by an empty overwrite of the original saved state.
        load(context)
        persist(context, library)
    }

    @Synchronized fun edit(context: Context, change: (MusicLibrary) -> MusicLibrary): MusicLibrary {
        val updated = change(load(context))
        persist(context, updated)
        return updated
    }

    private fun persist(context: Context, library: MusicLibrary) {
        val raw = MusicLibraryCodec.encode(library)
        val frozen = MusicLibraryCodec.decode(raw)
        if (!context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, raw).commit()) {
            cachedRaw = null
            cachedLibrary = null
            throw MusicLibraryException("The music library could not be saved. Free some storage and try again.")
        }
        cachedRaw = raw
        cachedLibrary = frozen
    }
}
