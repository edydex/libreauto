// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Xml
import org.bluetoothauto.model.Track
import org.bluetoothauto.playback.MusicLibrary
import org.bluetoothauto.playback.MusicLibraryCodec
import org.bluetoothauto.playback.MusicLibraryException
import org.bluetoothauto.playback.MusicLibraryStore
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Native persistence checks use only a uniquely allocated preference file and synthetic media URIs. */
object MusicLibraryStoreAcceptance {
    fun run(test: Instrumentation): String {
        val target = test.targetContext
        val allocated = "music-library-acceptance-${SystemClock.elapsedRealtimeNanos()}"
        val file = File(target.applicationInfo.dataDir, "shared_prefs/$allocated.xml")
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "bluetoothauto") { "Test attempted to access an unexpected preference file" }
                return target.getSharedPreferences(allocated, mode)
            }
        }
        val preferences = context.getSharedPreferences("bluetoothauto", Context.MODE_PRIVATE)
        val key = "musicLibraryV1"
        val tracks = List(149) { index ->
            Track("content://org.bluetoothauto.acceptance/document/${index % 41}", "Synthetic track $index", "Test artist")
        }
        val root = "content://org.bluetoothauto.acceptance/tree/primary%3AMusic%2FTest"
        val rawQueue = JSONArray().apply {
            tracks.forEach { put(JSONObject().put("uri", it.uri).put("title", it.title).put("artist", it.artist)) }
        }.toString()
        val rawGrants = JSONArray().put(root).toString()
        var assertions = 0
        fun verify(condition: Boolean, message: String) { assertions++; check(condition) { message } }
        fun expectReadError(block: () -> Unit) {
            try {
                block()
                error("Invalid persisted library unexpectedly succeeded")
            } catch (error: MusicLibraryException) {
                verify(!error.message.isNullOrBlank(), "Read failure did not have a usable explanation")
            }
        }
        fun diskString(name: String): String {
            val parser = Xml.newPullParser()
            file.inputStream().use { input ->
                parser.setInput(input, "UTF-8")
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string" && parser.getAttributeValue(null, "name") == name) {
                        return parser.nextText()
                    }
                    parser.next()
                }
            }
            error("Expected committed test preference was not present on disk")
        }
        try {
            verify(preferences.all.isEmpty(), "Disposable preference file was not empty")
            verify(preferences.edit().putString("tracks", rawQueue).putString("musicGrants", rawGrants)
                .putBoolean("shuffle", true).putString("navigator", "OPEN").putString("sentinel", "preserve me").commit(), "Test baseline did not commit")
            val baseline = preferences.all.toMap()
            fun preserved() {
                verify(preferences.all.filterKeys { it != key } == baseline, "Library operation changed the current queue, shuffle, URI grants or unrelated settings")
            }

            val migrated = MusicLibraryStore.load(context)
            verify(migrated.playlists.single().name == "Your music", "Legacy playlist was not named correctly")
            verify(migrated.playlists.single().tracks == tracks, "Legacy 149-track order or duplicate occurrences changed")
            verify(migrated.folders.single().treeUri == root && migrated.folders.single().name == "Test", "Legacy folder root was not retained")
            val migratedDisk = diskString(key)
            verify(MusicLibraryCodec.decode(migratedDisk) == migrated, "Migration did not durably commit its complete library")
            val loaded = MusicLibraryStore.load(context)
            verify(loaded == migrated, "Repeated load changed migrated IDs, order or media")
            verify(MusicLibraryStore.load(context) === loaded, "Unchanged library was needlessly reparsed")
            preserved()

            val legacyId = migrated.playlists.single().id
            val first = tracks[0]
            val second = tracks[1]
            var edited = MusicLibraryStore.edit(context) { library ->
                library.createPlaylist("Road trip", listOf(first, second, first), "trip")
                    .createPlaylist("Evening", listOf(second), "evening")
                    .renamePlaylist("trip", "  Commute  ")
                    .movePlaylist("trip", 0)
                    .moveTrack("trip", 1, 0)
                    .removeTrack("trip", 2)
                    .addTracks("trip", listOf(first, first))
                    .renameFolder(library.folders.single().id, "Trips")
            }
            verify(edited.playlists.map { it.id } == listOf("trip", legacyId, "evening"), "Playlist reordering did not persist")
            verify(edited.requirePlaylist("trip").name == "Commute", "Renaming was not normalized or saved")
            verify(edited.requirePlaylist("trip").tracks == listOf(second, first, first, first), "Track editing removed the wrong duplicate or lost ordering")
            verify(edited.requirePlaylist(legacyId).tracks == tracks, "Editing a different playlist altered the legacy source")
            verify(edited.folders.single().name == "Trips", "Folder rename was not saved")
            verify(MusicLibraryCodec.decode(diskString(key)) == edited, "Edited library differs from its committed disk data")
            preserved()

            edited = MusicLibraryStore.edit(context) { library ->
                library.removePlaylist("evening").removeFolder(library.folders.single().id)
            }
            verify(edited.playlists.map { it.id } == listOf("trip", legacyId), "Deleting one playlist removed another")
            verify(edited.folders.isEmpty(), "Folder removal was not saved")
            verify(edited.requirePlaylist(legacyId).tracks == tracks, "Removing a folder changed a saved playlist")
            preserved()

            val editedRaw = diskString(key)
            val replacement = edited.renamePlaylist("trip", "Reloaded from disk")
            verify(preferences.edit().putString(key, MusicLibraryCodec.encode(replacement)).commit(), "Replacement test data failed to commit")
            val reloaded = MusicLibraryStore.load(context)
            verify(reloaded == replacement && reloaded !== edited, "A changed raw value incorrectly reused stale cached state")
            verify(MusicLibraryStore.load(context) === reloaded, "Reloaded data did not become the new cache entry")
            verify(preferences.edit().putString(key, editedRaw).commit(), "Original edited test data failed to restore")
            verify(MusicLibraryStore.load(context) == edited, "Returning to previous raw data retained the wrong cached library")
            preserved()

            listOf("{", "{\"version\":2,\"playlists\":[],\"folders\":[]}", "{\"version\":1,\"playlists\":[null],\"folders\":[]}").forEach { corrupt ->
                verify(preferences.edit().putString(key, corrupt).commit(), "Corruption fixture did not commit")
                expectReadError { MusicLibraryStore.load(context) }
                expectReadError { MusicLibraryStore.save(context, MusicLibrary()) }
                expectReadError { MusicLibraryStore.edit(context) { MusicLibrary() } }
                verify(preferences.getString(key, null) == corrupt && diskString(key) == corrupt, "Failed load or save overwrote unreadable original data")
                preserved()
            }

            verify(preferences.edit().remove(key).putString("tracks", "[{\"title\":\"Missing URI\"}]").commit(), "Malformed legacy fixture did not commit")
            expectReadError { MusicLibraryStore.load(context) }
            verify(!preferences.contains(key), "Malformed legacy queue was replaced by an empty library")
            verify(preferences.getString("tracks", null) == "[{\"title\":\"Missing URI\"}]", "Malformed legacy queue was modified during failed migration")
            verify(preferences.getString("musicGrants", null) == rawGrants, "Failed migration changed selected-file permissions")

            return JSONObject().put("assertions", assertions).put("legacy_tracks", tracks.size).put("legacy_folder_roots", 1)
                .put("queue_and_grants_preserved", true).put("disk_commit_roundtrip", true).put("cache_invalidation", true)
                .put("corrupt_data_not_overwritten", true).put("preferences_isolated", true).toString()
        } finally {
            check(target.deleteSharedPreferences(allocated)) { "Disposable music-library test preferences were not removed" }
            check(!file.exists()) { "Disposable music-library preference file remained after cleanup" }
        }
    }
}
