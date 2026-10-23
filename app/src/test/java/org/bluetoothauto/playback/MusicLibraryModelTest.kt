// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.bluetoothauto.model.Track
import org.junit.Assert.*
import org.junit.Test

class MusicLibraryModelTest {
    private val first = Track("content://music/document/first", "First", "Artist")
    private val second = Track("content://music/document/second", "Second")
    private fun library() = MusicLibrary().createPlaylist("Morning", listOf(first, second, first), "morning")
        .createPlaylist("Evening", listOf(second), "evening")

    @Test fun editsPreserveIndependentListsAndDuplicateEntries() {
        val original = library()
        val edited = original.renamePlaylist("morning", "  Commute  ")
            .removeTrack("morning", 2).addTracks("morning", listOf(second, second))
        assertEquals("Commute", edited.requirePlaylist("morning").name)
        assertEquals(listOf(first, second, second, second), edited.requirePlaylist("morning").tracks)
        assertEquals(listOf(second), edited.requirePlaylist("evening").tracks)
        assertEquals(listOf(first, second, first), original.requirePlaylist("morning").tracks)
        assertEquals("Morning", original.requirePlaylist("morning").name)
    }
    @Test fun movePlaylistCanMoveFirstToLastAndBack() {
        val original = library()
        val moved = original.movePlaylist("morning", 1)
        assertEquals(listOf("evening", "morning"), moved.playlists.map { it.id })
        assertEquals(original, moved.movePlaylist("morning", 0))
    }
    @Test fun moveTrackUsesOccurrenceIndexNotUri() {
        val list = library().moveTrack("morning", 1, 0).requirePlaylist("morning").tracks
        assertEquals(listOf(second, first, first), list)
    }
    @Test fun removeOneDuplicateLeavesTheOtherOccurrence() {
        assertEquals(listOf(second, first), library().removeTrack("morning", 0).requirePlaylist("morning").tracks)
    }
    @Test fun deletingOnePlaylistDoesNotDeleteAudioInAnother() {
        val edited = library().removePlaylist("morning")
        assertEquals(listOf("evening"), edited.playlists.map { it.id })
        assertEquals(listOf(second), edited.requirePlaylist("evening").tracks)
    }
    @Test fun replacingTracksDoesNotChangeListOrderOrNames() {
        val original = library()
        val replaced = original.replacePlaylistTracks("morning", listOf(second))
        assertEquals(original.playlists.map { it.id to it.name }, replaced.playlists.map { it.id to it.name })
        assertEquals(listOf(second), replaced.requirePlaylist("morning").tracks)
    }
    @Test fun folderEditsLeavePlaylistsAndTrackAccessUrisIntact() {
        val original = library()
        val withFolder = original.addFolder("  Music  ", "content://music/tree/primary%3AMusic", "folder")
        assertEquals("Music", withFolder.folders.single().name)
        assertEquals(original.playlists, withFolder.playlists)
        assertEquals("Trips", withFolder.renameFolder("folder", "Trips").folders.single().name)
        assertEquals(original, withFolder.removeFolder("folder"))
    }
    @Test fun duplicateRootIsRejectedWithoutChangingOriginal() {
        val original = library().addFolder("Music", "content://music/tree/root", "root")
        fails { original.addFolder("Another", "content://music/tree/root", "another") }
        assertEquals(1, original.folders.size)
    }
    @Test fun playlistNamesCanMatchButStableIdsCannot() {
        assertEquals(2, MusicLibrary().createPlaylist("Music", id = "one").createPlaylist("Music", id = "two").playlists.size)
        fails { library().createPlaylist("Another", id = "morning") }
        fails { library().addFolder("Music", "content://music/tree/root", "morning") }
    }
    @Test fun blankAndMultilineNamesAreRejected() {
        listOf(" ", "\t", "Two\nlines", "Two\rLines", "Null\u0000name").forEach { name ->
            fails { library().createPlaylist(name) }
            fails { library().renamePlaylist("morning", name) }
        }
    }
    @Test fun nameLengthBoundaryIsExplicit() {
        assertEquals(100, MusicLibrary().createPlaylist("x".repeat(100), id = "ok").playlists.single().name.length)
        fails { MusicLibrary().createPlaylist("x".repeat(101)) }
    }
    @Test fun missingItemsNeverTurnIntoSuccessfulNoOps() {
        val original = library()
        fails { original.removePlaylist("missing") }
        fails { original.renamePlaylist("missing", "New") }
        fails { original.addTracks("missing", listOf(first)) }
        fails { original.removeFolder("missing") }
        fails { original.renameFolder("missing", "New") }
    }
    @Test fun movingOutsideBoundsIsRejectedAndSameIndexIsSafe() {
        val original = library()
        assertEquals(original, original.movePlaylist("morning", 0))
        assertEquals(original, original.moveTrack("morning", 1, 1))
        fails { original.movePlaylist("morning", -1) }
        fails { original.movePlaylist("morning", 2) }
        fails { original.moveTrack("morning", 0, 3) }
        fails { original.moveTrack("morning", -1, 0) }
        fails { original.removeTrack("morning", 3) }
    }
    @Test fun playlistCapacityRejectsTheWholeEdit() {
        val full = MusicLibrary().createPlaylist("Full", List(MusicLibrary.MAX_TRACKS) { first }, "full")
        fails { full.addTracks("full", listOf(second)) }
        assertEquals(MusicLibrary.MAX_TRACKS, full.requirePlaylist("full").tracks.size)
    }
    @Test fun libraryCapacityRejectsWholeNewPlaylist() {
        val full = MusicLibrary(playlists = List(MusicLibrary.MAX_PLAYLISTS) { MusicPlaylist("id$it", "List $it") }).validate()
        fails { full.createPlaylist("One more") }
        assertEquals(MusicLibrary.MAX_PLAYLISTS, full.playlists.size)
    }
    @Test fun totalTrackCapacityAlsoCountsRepeatedEntries() {
        val full = MusicLibrary(playlists = List(16) { MusicPlaylist("id$it", "List $it", List(512) { first }) }).validate()
        assertEquals(8192, full.playlists.sumOf { it.tracks.size })
        fails { full.createPlaylist("One more", listOf(first)) }
    }
    @Test fun folderCapacityIsBounded() {
        val full = MusicLibrary(folders = List(MusicLibrary.MAX_FOLDERS) { MusicFolder("id$it", "Folder $it", "content://music/tree/root$it") }).validate()
        fails { full.addFolder("One more", "content://music/tree/extra") }
    }
    @Test fun onlyLocalMediaUrisAndTreeFoldersAreAccepted() {
        listOf("https://example.org/song.mp3", "javascript:alert(1)", "content://", "file://", "").forEach { uri ->
            fails { MusicLibrary().createPlaylist("Bad", listOf(first.copy(uri = uri))) }
        }
        assertEquals(1, MusicLibrary().createPlaylist("Short", listOf(first.copy(uri = "file:///a"))).playlists.size)
        fails { MusicLibrary().addFolder("Bad", "file:///Music") }
        fails { MusicLibrary().addFolder("Bad", "content://music/document/song") }
    }
    @Test fun oversizeUrisAndLabelsDoNotGetSilentlyTruncated() {
        fails { MusicLibrary().createPlaylist("Bad", listOf(first.copy(uri = "content://music/" + "x".repeat(MusicLibrary.MAX_URI_LENGTH)))) }
        fails { MusicLibrary().createPlaylist("Bad", listOf(first.copy(title = "x".repeat(MusicLibrary.MAX_TRACK_LABEL_LENGTH + 1)))) }
        fails { MusicLibrary().createPlaylist("Bad", listOf(first.copy(artist = "x".repeat(MusicLibrary.MAX_TRACK_LABEL_LENGTH + 1)))) }
    }

    private fun fails(block: () -> Unit) {
        try { block(); fail("Expected a readable library error") } catch (error: MusicLibraryException) { assertTrue(error.message.orEmpty().isNotBlank()) }
    }
}
