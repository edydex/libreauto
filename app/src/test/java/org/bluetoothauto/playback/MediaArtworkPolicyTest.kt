// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.junit.Assert.*
import org.junit.Test

class MediaArtworkPolicyTest {
    @Test fun actualTagsWinOverFileNames() {
        assertEquals("A real title", MediaArtworkPolicy.title(" A real title ", "01-file.mp3"))
        assertEquals("Song.mp3", MediaArtworkPolicy.title("Song.mp3", "fallback.wav"))
    }

    @Test fun missingTitleUsesReadableFileName() {
        assertEquals("01 - Morning drive", MediaArtworkPolicy.title(null, "01 - Morning drive.FLAC"))
        assertEquals("Recording.part.one", MediaArtworkPolicy.title("", "Recording.part.one.wav"))
        assertEquals("A title.v2", MediaArtworkPolicy.title(null, "A title.v2"))
        assertEquals("Unknown track", MediaArtworkPolicy.title(null, "  "))
    }

    @Test fun controlCharactersAndOversizedTagsCannotClutterPlayer() {
        assertEquals("Artist Name Live", MediaArtworkPolicy.clean("Artist\u0000Name\n\tLive"))
        assertEquals(160, MediaArtworkPolicy.clean("x".repeat(10_000)).length)
        assertEquals("", MediaArtworkPolicy.clean(null))
    }

    @Test fun durationAllowsAudiobooksAndRejectsBrokenValues() {
        assertEquals(90_000L, MediaArtworkPolicy.duration("90000"))
        assertEquals(36L * 3600 * 1000, MediaArtworkPolicy.duration((36L * 3600 * 1000).toString()))
        listOf(null, "-1", "0", "1.5", "unknown", Long.MAX_VALUE.toString()).forEach {
            assertEquals(0L, MediaArtworkPolicy.duration(it))
        }
    }

    @Test fun smallCoversKeepOriginalPixels() {
        assertEquals(1, MediaArtworkPolicy.sampleSize(500, 500))
        assertEquals(1, MediaArtworkPolicy.sampleSize(1, 512))
    }

    @Test fun largeAndWideCoversHaveBoundedDecodeDimensions() {
        listOf(513 to 512, 4096 to 4096, 10_000 to 1, 65_536 to 65_536).forEach { (width, height) ->
            val sample = requireNotNull(MediaArtworkPolicy.sampleSize(width, height))
            assertTrue((maxOf(width, height).toLong() + sample - 1) / sample <= 512)
            assertEquals(0, sample and (sample - 1))
        }
    }

    @Test fun brokenImageBoundsAreRejectedBeforePixelDecode() {
        listOf(0 to 100, -1 to -1, 65_537 to 100, Int.MAX_VALUE to 1).forEach { (width, height) ->
            assertNull(MediaArtworkPolicy.sampleSize(width, height))
        }
    }
}
