// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.bluetoothauto.model.Track
import org.junit.Assert.*
import org.junit.Test

class MusicReorderPolicyTest {
    @Test fun controlsHeaderNeverConsumesASavedPlaylistPosition() {
        assertEquals(0, MusicReorderPolicy.targetIndex(0, 1, 3))
        assertEquals(0, MusicReorderPolicy.targetIndex(1, 1, 3))
        assertEquals(1, MusicReorderPolicy.targetIndex(2, 1, 3))
        assertEquals(2, MusicReorderPolicy.targetIndex(3, 1, 3))
    }

    @Test fun severalHeaderViewsStillMapTheFirstActualRowToZero() {
        (0..4).forEach { adapterPosition -> assertEquals(0, MusicReorderPolicy.targetIndex(adapterPosition, 4, 3)) }
        assertEquals(1, MusicReorderPolicy.targetIndex(5, 4, 3))
        assertEquals(2, MusicReorderPolicy.targetIndex(6, 4, 3))
    }

    @Test fun droppingBelowTheLastMusicRowClampsToTheEnd() {
        assertEquals(2, MusicReorderPolicy.targetIndex(4, 1, 3))
        assertEquals(2, MusicReorderPolicy.targetIndex(Int.MAX_VALUE, 1, 3))
    }

    @Test fun listWithoutAHeaderUsesItsActualRowPositions() {
        assertEquals(0, MusicReorderPolicy.targetIndex(0, 0, 4))
        assertEquals(2, MusicReorderPolicy.targetIndex(2, 0, 4))
        assertEquals(3, MusicReorderPolicy.targetIndex(20, 0, 4))
    }

    @Test fun listViewInvalidPositionDoesNotBecomeAMoveToTheTop() {
        assertNull(MusicReorderPolicy.targetIndex(-1, 1, 3))
        assertNull(MusicReorderPolicy.targetIndex(Int.MIN_VALUE, 0, 3))
    }

    @Test fun headerOnlyAndEmptyListsHaveNoValidDropTarget() {
        listOf(0, 1, 50, Int.MAX_VALUE).forEach { position ->
            assertNull(MusicReorderPolicy.targetIndex(position, 1, 0))
            assertNull(MusicReorderPolicy.targetIndex(position, 0, 0))
        }
        assertNull(MusicReorderPolicy.targetIndex(0, 1, -1))
    }

    @Test fun invalidHeaderCountsCannotMoveAnySavedItem() {
        assertNull(MusicReorderPolicy.targetIndex(2, -1, 3))
        assertNull(MusicReorderPolicy.targetIndex(Int.MAX_VALUE, Int.MIN_VALUE, 3))
    }

    @Test fun singleItemListOnlyHasItsExistingPosition() {
        listOf(0, 1, 2, Int.MAX_VALUE).forEach { assertEquals(0, MusicReorderPolicy.targetIndex(it, 1, 1)) }
    }

    @Test fun maximumSupportedPlaylistAndTrackCountsHaveReachableFinalRows() {
        listOf(MusicLibrary.MAX_PLAYLISTS, MusicLibrary.MAX_TRACKS).forEach { rowCount ->
            assertEquals(0, MusicReorderPolicy.targetIndex(1, 1, rowCount))
            assertEquals(rowCount - 1, MusicReorderPolicy.targetIndex(rowCount, 1, rowCount))
            assertEquals(rowCount - 1, MusicReorderPolicy.targetIndex(rowCount + 1, 1, rowCount))
        }
    }

    @Test fun extremeNonnegativeIndicesDoNotOverflowIntoTheOppositeEnd() {
        assertEquals(0, MusicReorderPolicy.targetIndex(0, Int.MAX_VALUE, 3))
        assertEquals(0, MusicReorderPolicy.targetIndex(Int.MAX_VALUE, Int.MAX_VALUE, 3))
        assertEquals(Int.MAX_VALUE - 1, MusicReorderPolicy.targetIndex(Int.MAX_VALUE, 0, Int.MAX_VALUE))
    }

    @Test fun droppingAPlaylistOnTheControlsHeaderMovesOnlyThatPlaylistToTheTop() {
        val original = MusicLibrary(playlists = List(4) { MusicPlaylist("playlist-$it", "List $it") }).validate()
        val target = requireNotNull(MusicReorderPolicy.targetIndex(0, 1, original.playlists.size))
        val reordered = original.movePlaylist("playlist-3", target)
        assertEquals(listOf("playlist-3", "playlist-0", "playlist-1", "playlist-2"), reordered.playlists.map { it.id })
        assertEquals(listOf("playlist-0", "playlist-1", "playlist-2", "playlist-3"), original.playlists.map { it.id })
        assertEquals(reordered, MusicLibraryCodec.decode(MusicLibraryCodec.encode(reordered)))
    }

    @Test fun draggingOneRepeatedMediaOccurrenceKeepsEveryOtherTrackInOrder() {
        val a = Track("content://music/document/a", "First occurrence")
        val b = Track("content://music/document/b", "Middle")
        val repeated = a.copy(title = "Repeated occurrence")
        val c = Track("content://music/document/c", "Last")
        val original = MusicLibrary().createPlaylist("Commute", listOf(a, b, repeated, c), "commute")
        val target = requireNotNull(MusicReorderPolicy.targetIndex(1, 1, 4))
        val reordered = original.moveTrack("commute", 2, target)
        assertEquals(listOf(repeated, a, b, c), reordered.requirePlaylist("commute").tracks)
        assertEquals(listOf(a, b, repeated, c), original.requirePlaylist("commute").tracks)
        assertEquals(2, reordered.requirePlaylist("commute").tracks.count { it.uri == a.uri })
        assertEquals(reordered, MusicLibraryCodec.decode(MusicLibraryCodec.encode(reordered)))
    }

    @Test fun droppingOnTheCurrentRowDoesNotChangeTrackOrder() {
        val tracks = List(4) { Track("content://music/document/$it", "Track $it") }
        val original = MusicLibrary().createPlaylist("Commute", tracks, "commute")
        val target = requireNotNull(MusicReorderPolicy.targetIndex(3, 1, tracks.size))
        assertEquals(original, original.moveTrack("commute", 2, target))
    }
}
