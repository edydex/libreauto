// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.bluetoothauto.model.Track
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicLibraryCodecTest {
    private val song = Track("content://music/document/a", "Song: \"first\" 🌙", "Artist")
    private fun fixture() = MusicLibrary().createPlaylist("Evening 🌙", listOf(song, song), "evening")
        .addFolder("Music", "content://music/tree/primary%3AMusic", "music")

    @Test fun roundTripKeepsNamesOrderDuplicateTracksAndSelectedRoots() {
        val original = fixture().createPlaylist("Second", emptyList(), "second")
        assertEquals(original, MusicLibraryCodec.decode(MusicLibraryCodec.encode(original)))
    }
    @Test fun emptyLibraryRoundTrips() {
        assertEquals(MusicLibrary(), MusicLibraryCodec.decode(MusicLibraryCodec.encode(MusicLibrary())))
    }
    @Test fun legacyMigrationKeeps149TracksIncludingDuplicatesInTheirOriginalOrder() {
        val tracks = List(149) { song.copy(title = "Track $it", uri = "content://music/document/${it % 41}") }
        val raw = JSONArray().apply { tracks.forEach { put(JSONObject().put("uri", it.uri).put("title", it.title).put("artist", it.artist)) } }.toString()
        val migrated = MusicLibraryCodec.migrateLegacy(raw)
        assertEquals("Your music", migrated.playlists.single().name)
        assertEquals("legacy-your-music", migrated.playlists.single().id)
        assertEquals(tracks, migrated.playlists.single().tracks)
        assertEquals(migrated, MusicLibraryCodec.decode(MusicLibraryCodec.encode(migrated)))
        assertEquals(raw, JSONArray(raw).toString())
    }
    @Test fun emptyLegacyQueueDoesNotCreateAnEmptyPlaceholderPlaylist() {
        assertEquals(MusicLibrary(), MusicLibraryCodec.migrateLegacy("[]"))
    }
    @Test fun legacyFolderRootsAreMigratedOnceAndIndividualFileGrantsAreRetainedOutsideTheLibrary() {
        val uri = "content://music/tree/primary%3AMusic%2FCommute%2B"
        val grants = JSONArray().put(uri).put(song.uri).put(uri).toString()
        val migrated = MusicLibraryCodec.migrateLegacy("[]", grants)
        assertEquals("Commute+", migrated.folders.single().name)
        assertEquals(uri, migrated.folders.single().treeUri)
        assertEquals(migrated, MusicLibraryCodec.migrateLegacy("[]", grants))
        assertEquals(migrated, MusicLibraryCodec.decode(MusicLibraryCodec.encode(migrated)))
    }
    @Test fun legacyIndividualFileGrantsDoNotCreateFolders() {
        assertTrue(MusicLibraryCodec.migrateLegacy("[]", JSONArray().put(song.uri).toString()).folders.isEmpty())
    }
    @Test fun absentLegacyLabelsUseThePreviousQueueDefaults() {
        val migrated = MusicLibraryCodec.migrateLegacy("[{\"uri\":\"content://music/document/a\"}]")
        assertEquals(Track("content://music/document/a", "Music", ""), migrated.playlists.single().tracks.single())
    }
    @Test fun malformedLegacyQueueIsNotReplacedWithAnEmptyLibrary() {
        listOf("{", "{}", "[null]", "[{\"title\":\"Missing URI\"}]", "[\"not a track\"]").forEach { fails { MusicLibraryCodec.migrateLegacy(it) } }
    }
    @Test fun unsupportedOrMissingVersionIsNotTreatedAsAnEmptyLibrary() {
        listOf("{\"playlists\":[],\"folders\":[]}", "{\"version\":2,\"playlists\":[],\"folders\":[]}").forEach { fails { MusicLibraryCodec.decode(it) } }
    }
    @Test fun malformedSavedLibraryIsRejectedWithoutSalvagingOnlySomeEntries() {
        val root = JSONObject(MusicLibraryCodec.encode(fixture()))
        root.getJSONArray("playlists").put(JSONObject().put("id", "broken").put("name", "Broken").put("tracks", JSONArray().put(JSONObject().put("title", "Missing URI"))))
        fails { MusicLibraryCodec.decode(root.toString()) }
    }
    @Test fun wrongTopLevelTypesAndNullsAreRejected() {
        listOf("{\"version\":1,\"playlists\":null,\"folders\":[]}", "{\"version\":1,\"playlists\":{},\"folders\":[]}", "[]", "null", "").forEach {
            fails { MusicLibraryCodec.decode(it) }
        }
    }
    @Test fun serializedOverlongNamesAndRepeatedIdsAreRejected() {
        val root = JSONObject(MusicLibraryCodec.encode(fixture()))
        root.getJSONArray("playlists").getJSONObject(0).put("name", "x".repeat(101))
        fails { MusicLibraryCodec.decode(root.toString()) }
        root.getJSONArray("playlists").getJSONObject(0).put("name", "Valid")
        root.getJSONArray("folders").getJSONObject(0).put("id", "evening")
        fails { MusicLibraryCodec.decode(root.toString()) }
    }
    @Test fun legacyOverflowIsRejectedRatherThanDroppingTheLastTrack() {
        val raw = JSONArray().apply { repeat(513) { put(JSONObject().put("uri", song.uri).put("title", song.title)) } }.toString()
        fails { MusicLibraryCodec.migrateLegacy(raw) }
    }
    @Test fun savedPlaylistOverflowIsRejectedBeforeDecodingTracks() {
        val root = JSONObject(MusicLibraryCodec.encode(MusicLibrary()))
        repeat(65) { root.getJSONArray("playlists").put(JSONObject().put("id", "id$it").put("name", "List $it").put("tracks", JSONArray())) }
        fails { MusicLibraryCodec.decode(root.toString()) }
    }
    @Test fun encodedSizeIsBoundedBeforeLargeJsonAllocation() {
        val large = song.copy(title = "\u0001".repeat(2048), artist = "\u0002".repeat(2048))
        val library = MusicLibrary().createPlaylist("Many", List(512) { large }, "many")
        fails { MusicLibraryCodec.encode(library) }
    }
    @Test fun oversizedInputCannotBeSilentlyReset() {
        val raw = " ".repeat(MusicLibraryCodec.MAX_JSON_LENGTH + 1)
        fails { MusicLibraryCodec.decode(raw) }
        fails { MusicLibraryCodec.migrateLegacy(raw) }
    }
    @Test fun nonlocalTrackDoesNotDisappearDuringLegacyMigration() {
        val raw = "[{\"uri\":\"https://example.org/song.mp3\",\"title\":\"Remote\"}]"
        fails { MusicLibraryCodec.migrateLegacy(raw) }
    }

    private fun fails(block: () -> Unit) {
        try { block(); fail("Expected a readable library error") } catch (error: MusicLibraryException) { assertTrue(error.message.orEmpty().isNotBlank()) }
    }
}
