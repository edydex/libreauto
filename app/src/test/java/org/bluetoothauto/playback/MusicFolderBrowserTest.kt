// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.util.concurrent.CancellationException

class MusicFolderBrowserTest {
    private fun directory(id: String, name: String = id) =
        MusicFolderDocument(id, name, MusicFolderPolicy.DIRECTORY_MIME)
    private fun audio(id: String, name: String = "$id.mp3", mime: String = "audio/mpeg") =
        MusicFolderDocument(id, name, mime)

    @Test fun selectingRootDoesNotNeedADescendantQuery() {
        for (requested in listOf(null, "opaque-root")) {
            val selected = MusicFolderPolicy.selectDirectory("opaque-root", requested,
                { error("Root should not query isChild") }, { error("Root should not scan descendants") }, {})
            assertEquals("opaque-root", selected)
        }
    }

    @Test fun providerMembershipAllowsOpaqueIdsWithoutPathAssumptions() {
        val selected = MusicFolderPolicy.selectDirectory("volume:root", "uuid-no-shared-prefix",
            { true }, { error("Supported provider must not scan the whole tree") }, {})
        assertEquals("uuid-no-shared-prefix", selected)
    }

    @Test fun providerDenialDoesNotQueryTheUnapprovedFolder() {
        assertThrows(SecurityException::class.java) {
            MusicFolderPolicy.selectDirectory("root", "other-tree", { false },
                { error("Must not query denied folder") }, {})
        }
    }

    @Test fun unsupportedProviderProvesMembershipByWalkingOnlyGrantedDescendants() {
        val queried = mutableListOf<String>()
        val tree = mapOf("root" to listOf(directory("branch")),
            "branch" to listOf(directory("unrelated:opaque-id", "Album")))
        assertEquals("unrelated:opaque-id", MusicFolderPolicy.selectDirectory("root", "unrelated:opaque-id",
            { null }, { queried.add(it); tree[it] ?: emptyList() }, {}))
        assertEquals(listOf("root", "branch"), queried)
    }

    @Test fun unavailableAndNonDirectoryIdsAreNotAcceptedByFallback() {
        for (target in listOf("outside", "root-prefix-but-not-a-child", "song")) {
            val queried = mutableListOf<String>()
            assertThrows(SecurityException::class.java) {
                MusicFolderPolicy.selectDirectory("root", target, { null }, {
                    queried.add(it)
                    if (it == "root") listOf(audio("song"), directory("album")) else emptyList()
                }, {})
            }
            assertEquals(listOf("root", "album"), queried)
        }
    }

    @Test fun fallbackHandlesProviderCyclesAndNeverLoopsOrReadsAnOutsideId() {
        val queried = mutableListOf<String>()
        assertThrows(SecurityException::class.java) {
            MusicFolderPolicy.selectDirectory("root", "outside", { null }, {
                queried.add(it)
                listOf(directory("root"), directory("album"))
            }, {})
        }
        assertEquals(listOf("root", "album"), queried)
    }

    @Test fun fallbackDeniesWhenDepthProofWouldExceedLimit() {
        val queried = mutableListOf<String>()
        assertThrows(SecurityException::class.java) {
            MusicFolderPolicy.selectDirectory("0", "13", { null }, {
                queried.add(it); listOf(directory((it.toInt() + 1).toString()))
            }, {})
        }
        assertEquals((0..MusicFolderPolicy.MAX_DEPTH).map(Int::toString), queried)
    }

    @Test fun fallbackHasAnOverallDocumentBudget() {
        assertThrows(FileNotFoundException::class.java) {
            MusicFolderPolicy.selectDirectory("root", "outside", { null }, {
                (0..MusicFolderPolicy.MAX_DOCUMENTS).map { audio("id$it") }
            }, {})
        }
    }

    @Test fun cancellationIsPropagatedBeforeAnyUnnecessaryProviderRead() {
        assertThrows(CancellationException::class.java) {
            MusicFolderPolicy.selectDirectory("root", "child", { error("Cancelled query") },
                { error("Cancelled scan") }, { throw CancellationException() })
        }
    }

    @Test fun invalidIdsAndMalformedNamesNeverReachTheListingOrUriBuilder() {
        assertThrows(IllegalArgumentException::class.java) {
            MusicFolderPolicy.selectDirectory("root", "bad\u0000id", { error("Invalid ID") },
                { error("Invalid ID") }, {})
        }
        val listing = MusicFolderPolicy.listing("Music", "root", listOf(
            audio("good"), audio("bad\u0000id"), audio("slash", "escape/song.mp3"),
            audio("backslash", "escape\\song.mp3"), directory("dot", ".."), directory("empty", "")
        ), false) { id -> assertEquals("good", id); "content://test/$id" }
        assertEquals(listOf("content://test/good"), listing.tracks.map { it.uri })
        assertTrue(listing.folders.isEmpty())
    }

    @Test fun listingShowsImmediateFoldersAndAudioInNaturalOrderWithProviderUris() {
        val listing = MusicFolderPolicy.listing("Music", "root", listOf(
            audio("ten", "Track 10.mp3"), directory("d10", "Album 10"),
            audio("two", "Track 2.mp3"), directory("d2", "Album 2"),
            audio("playlist", "Favorites.m3u", "audio/x-mpegurl"),
            MusicFolderDocument("cover", "cover.jpg", "image/jpeg"),
            audio("two", "Track 2 duplicate.mp3")
        ), false) { "content://selected-tree/document/$it" }
        assertEquals(listOf("Album 2", "Album 10"), listing.folders.map { it.name })
        assertEquals(listOf("Track 2.mp3", "Track 10.mp3"), listing.tracks.map { it.title })
        assertEquals("content://selected-tree/document/two", listing.tracks.first().uri)
        assertFalse(listing.truncated)
    }

    @Test fun listingCapsFoldersAndTracksAndReportsBothProviderAndLocalLimits() {
        val audio = (1..513).map { audio("t$it", "Track $it.mp3") }
        val directories = (1..513).map { directory("d$it", "Album $it") }
        val listing = MusicFolderPolicy.listing("Music", "root", audio + directories, false) { "content://test/$it" }
        assertEquals(512, listing.tracks.size)
        assertEquals(512, listing.folders.size)
        assertTrue(listing.truncated)
        assertEquals("Track 512.mp3", listing.tracks.last().title)
        assertTrue(MusicFolderPolicy.listing("Music", "root", emptyList(), true) { it }.truncated)
        assertFalse(MusicFolderPolicy.listing("Music", "root", audio.take(512), false) { it }.truncated)
    }
}
