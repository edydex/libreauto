// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.bluetoothauto.model.Track
import org.junit.Assert.*
import org.junit.Test

class PlayerQueueTest {
    private val tracks = listOf(Track("file:///one.mp3", "One"), Track("content://audio/two", "Two"))

    @Test fun automaticCompletionStopsAtQueueEnd() {
        val queue = PlayerQueue()
        queue.replace(tracks, 0)
        assertTrue(queue.moveNext(wrap = false))
        assertEquals("Two", queue.current?.title)
        assertFalse(queue.moveNext(wrap = false))
        assertEquals(1, queue.index)
    }

    @Test fun intentionalNextWraps() {
        val queue = PlayerQueue()
        queue.replace(tracks, 1)
        assertTrue(queue.moveNext(wrap = true))
        assertEquals("One", queue.current?.title)
    }

    @Test fun previousNeverUnderflows() {
        val queue = PlayerQueue()
        queue.replace(tracks, 1)
        queue.movePrevious()
        queue.movePrevious()
        assertEquals(0, queue.index)
    }

    @Test fun clearAndEmptyNextAreSafe() {
        val queue = PlayerQueue()
        queue.replace(tracks, 0)
        queue.replace(emptyList(), 10)
        assertNull(queue.current)
        assertEquals(-1, queue.index)
        assertFalse(queue.moveNext(wrap = true))
        queue.movePrevious()
        assertEquals(-1, queue.index)
    }

    @Test fun pickerQueueIsCopiedAndStartingIndexIsBounded() {
        val mutable = tracks.toMutableList()
        val queue = PlayerQueue()
        queue.replace(mutable, Int.MAX_VALUE)
        mutable.clear()
        assertEquals(2, queue.size)
        assertEquals(1, queue.index)
        queue.replace(tracks, -42)
        assertEquals(0, queue.index)
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedQueueIsRejected() {
        PlayerQueue().replace(List(513) { tracks.first() }, 0)
    }

    @Test fun shuffleHasNoRepeatedIndexInOneFiniteCycle() {
        val queue=PlayerQueue(kotlin.random.Random(42))
        queue.replace(List(12){Track("file:///$it.mp3","$it")},5)
        queue.setShuffle(true)
        val visited=mutableListOf(queue.index)
        while(queue.moveNext(false))visited.add(queue.index)
        assertEquals(12,visited.size);assertEquals(12,visited.toSet().size);assertEquals(5,visited.first())
    }
    @Test fun previousRetracesActualShuffledOrder() {
        val queue=PlayerQueue(kotlin.random.Random(7));queue.replace(List(8){Track("file:///$it.mp3","$it")},0);queue.setShuffle(true)
        queue.moveNext(false);val prior=queue.index;queue.moveNext(false);val selected=queue.index
        queue.movePrevious();assertEquals(prior,queue.index);queue.moveNext(false);assertEquals(selected,queue.index)
    }
    @Test fun switchingOrderPreservesCurrentEntry() {
        val queue=PlayerQueue(kotlin.random.Random(1));queue.replace(tracks,1)
        queue.setShuffle(true);assertEquals(1,queue.index);queue.setShuffle(false);assertEquals(1,queue.index)
    }
    @Test fun appendKeepsCurrentAndDoesNotCollapseIntentionalDuplicateEntries() {
        val queue=PlayerQueue();queue.replace(tracks,1)
        assertTrue(queue.update(tracks+tracks.first()));assertEquals(1,queue.index);assertEquals(3,queue.size)
        assertTrue(queue.moveNext(false));assertEquals(2,queue.index);assertFalse(queue.moveNext(false))
    }
    @Test fun deliberateNewShuffleCycleAvoidsImmediateRepeat() {
        val queue=PlayerQueue(kotlin.random.Random(12));queue.replace(tracks,0);queue.setShuffle(true)
        queue.moveNext(false);val last=queue.index
        assertTrue(queue.moveNext(true));assertNotEquals(last,queue.index)
        assertTrue(queue.moveNext(false));assertFalse(queue.moveNext(false))
    }
}
