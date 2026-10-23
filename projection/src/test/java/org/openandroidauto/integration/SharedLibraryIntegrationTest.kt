// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.integration

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

class SharedLibraryIntegrationTest {
    private class Player : SharedLibraryIntegration.PlaybackController {
        var keys = mutableListOf<Int>()
        var seeks = mutableListOf<Long>()
        var clears = 0
        override fun play(queue: List<Uri>, startIndex: Int) { }
        override fun mediaKey(keyCode: Int) { keys.add(keyCode) }
        override fun seekTo(positionMs: Long) { seeks.add(positionMs) }
        override fun clear() { clears++ }
    }
    @Test fun closingTheSessionReleasesAllPlayerCallbacksWithoutStartingAnything() {
        val player = Player()
        val lease = SharedLibraryIntegration.registerPlayback(player)
        assertTrue(SharedLibraryIntegration.mediaKey(85))
        assertTrue(SharedLibraryIntegration.seekTo(-1))
        assertEquals(listOf(85), player.keys)
        assertEquals(listOf(0L), player.seeks)
        lease.close()
        assertFalse(SharedLibraryIntegration.playbackReady())
        assertFalse(SharedLibraryIntegration.mediaKey(87))
        assertFalse(SharedLibraryIntegration.clearPlayback())
        assertEquals(listOf(85), player.keys)
    }
    @Test fun staleSessionCleanupCannotUnregisterItsReplacement() {
        val previous = Player()
        val old = SharedLibraryIntegration.registerPlayback(previous)
        val replacement = Player()
        val current = SharedLibraryIntegration.registerPlayback(replacement)
        old.close()
        try {
            assertEquals(1, previous.clears)
            assertTrue(SharedLibraryIntegration.mediaKey(88))
            assertEquals(listOf(88), replacement.keys)
        } finally { current.close() }
    }
}
