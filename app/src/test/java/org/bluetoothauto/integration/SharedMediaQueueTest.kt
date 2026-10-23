// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.bluetoothauto.model.Track
import org.junit.Assert.*
import org.junit.Test

class SharedMediaQueueTest {
    private val first = Track("content://music/a", "First", "Artist")
    private val second = Track("file:///storage/music/b.mp3", "Second", "Other")
    @Test fun shuffledDuplicateOccurrencesKeepSeparateOriginalQueuePositions() {
        assertEquals(listOf(0, 2, 1), SharedMediaQueue.originalIndices(listOf(first, second, first), listOf(first, first, second)))
    }
    @Test fun selectedOrderAndDuplicatesUseExistingPlaylistMetadata() {
        val selected = listOf(second.uri, first.uri, second.uri)
        assertEquals(listOf(second, first, second), SharedMediaQueue.replacement(selected, listOf(first), listOf(second)))
    }
    @Test fun clearingQueueDoesNotMutateEitherSourcePlaylistOrItsTracks() {
        val saved = mutableListOf(first, second)
        assertTrue(SharedMediaQueue.replacement(emptyList(), saved, saved).isEmpty())
        assertEquals(listOf(first, second), saved)
    }
    @Test fun all149LegacyTracksKeepTheirOrderMetadataAndDuplicates() {
        val tracks = List(149) { first.copy(uri = "content://music/${it % 20}", title = "Track ${it % 20}") }
        assertEquals(tracks, SharedMediaQueue.replacement(tracks.map { it.uri }, tracks, emptyList()))
    }
    @Test fun nonlocalQueueIsRejectedBeforeItCanReplaceTheCurrentQueue() {
        try { SharedMediaQueue.replacement(listOf("https://example.com/music.mp3"), listOf(first), emptyList()); fail("Remote media was accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun metricImperialAndAutomaticUnitsUseTheExistingHostPreference() {
        listOf("AUTO", "IMPERIAL", "METRIC").forEach { value ->
            assertEquals(value, SharedMediaQueue.hostSpeedUnits(SharedMediaQueue.projectionSpeedUnits(value)))
        }
    }
}
