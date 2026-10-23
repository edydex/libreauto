// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.junit.Assert.*
import org.junit.Test

class MusicImportPolicyTest {
    private val docs = listOf(
        AudioDocument("content://tree/opaque-a", "Track 2.mp3", "Album/Track 2.mp3"),
        AudioDocument("content://tree/opaque-b", "A+B #1.mp3", "Album/A+B #1.mp3"),
        AudioDocument("content://tree/opaque-c", "Literal%20name.mp3", "Literal%20name.mp3"),
        AudioDocument("content://tree/opaque-d", "Track 2.mp3", "Other/Track 2.mp3"))
    private fun resolve(path:String,base:String="",uri:Boolean=false)=PlaylistPathPolicy.resolve(path,docs,base,"Music",uri)

    @Test fun naturalNamesDoNotSortTenBeforeTwo(){
        assertEquals(listOf("1.mp3","2.mp3","10.mp3","100.mp3"),listOf("10.mp3","2.mp3","100.mp3","1.mp3").sortedWith(NaturalFileOrder))
    }
    @Test fun naturalOrderIsStableAcrossFoldersAndHugeNumbers(){
        val values=listOf("Disc 10/1.mp3","Disc 2/10.mp3","Disc 2/2.mp3")
        assertEquals(listOf("Disc 2/2.mp3","Disc 2/10.mp3","Disc 10/1.mp3"),values.sortedWith(NaturalFileOrder))
        assertTrue(NaturalFileOrder.compare("9".repeat(200),"1"+"0".repeat(200))<0)
    }
    @Test fun supportedAudioIsDistinctFromPlaylists(){
        assertTrue(AudioFilePolicy.accepts("song.FLAC","application/octet-stream"))
        assertTrue(AudioFilePolicy.accepts("song.unknown","audio/custom"))
        assertFalse(AudioFilePolicy.accepts("list.m3u","audio/x-mpegurl"))
        assertFalse(AudioFilePolicy.accepts("list.pls","audio/x-scpls"))
        assertFalse(AudioFilePolicy.accepts("cover.jpg","image/jpeg"))
    }
    @Test fun relativePlaylistDirectoryAndParentsStayWithinTree(){
        assertEquals(docs[0],resolve("../Album/Track 2.mp3","Playlists"))
        assertEquals(docs[0],resolve("Track 2.mp3","Album"))
        assertNull(resolve("../../Album/Track 2.mp3","Playlists"))
    }
    @Test fun xspfEscapesDecodeOnceAndPlusIsLiteral(){
        assertEquals(docs[1],resolve("Album/A+B%20%231.mp3",uri=true))
        assertEquals(docs[2],resolve("Literal%2520name.mp3",uri=true))
        assertNull(resolve("Literal%20name.mp3",uri=true))
    }
    @Test fun plainM3uPercentIsPartOfFilename(){assertEquals(docs[2],resolve("Literal%20name.mp3"))}
    @Test fun fileUriCanRelinkOnlyThroughChosenMatchingRoot(){
        assertEquals(docs[0],resolve("file:///storage/emulated/0/Music/Album/Track%202.mp3"))
        assertEquals(docs[0],resolve("C:\\Users\\person\\Music\\Album\\Track 2.mp3"))
        assertNull(resolve("C:\\Users\\person\\Unknown\\Album\\Track 2.mp3"))
    }
    @Test fun repeatedAbsoluteRootNamesCannotSilentlySelectAnotherSong(){
        val documents=listOf(AudioDocument("content://tree/root-song","song.mp3","song.mp3"),
            AudioDocument("content://tree/nested-song","song.mp3","Music/song.mp3"))
        assertNull(PlaylistPathPolicy.resolve("C:\\Music\\Music\\song.mp3",documents,"","Music",false))
        assertNull(PlaylistPathPolicy.resolve("file:///Music/Music/song.mp3",documents,"","Music",true))
        assertEquals(documents[1],PlaylistPathPolicy.resolve("Music/song.mp3",documents,"","Music",false))
    }
    @Test fun noBasenameGuessingAndNoNetworkAccess(){
        assertNull(resolve("Track 2.mp3"))
        assertNull(resolve("https://example.com/Album/Track%202.mp3"))
        assertNull(resolve("file://server/Music/Album/Track%202.mp3"))
        assertNull(resolve("\\\\server\\Music\\Album\\Track 2.mp3"))
        assertNull(resolve("content://other-provider/ungranted-file"))
    }
    @Test fun contentUriMustBeAnExactEnumeratedDescendant(){assertEquals(docs[0],resolve(docs[0].uri))}
    @Test fun encodedParentTraversalCannotEscapeChosenTree(){
        assertNull(resolve("%2e%2e/Album/Track%202.mp3",uri=true))
        assertNull(resolve("file:///Music/../../Album/Track%202.mp3"))
    }
    @Test fun ambiguousCaseFoldIsRejected(){
        val ambiguous=listOf(AudioDocument("content://a","A.mp3","A.mp3"),AudioDocument("content://b","a.mp3","a.mp3"))
        assertNull(PlaylistPathPolicy.resolve("a.MP3",ambiguous,"","Music",false))
    }
    @Test fun malformedOrUnboundedReferenceIsRejected(){
        assertNull(resolve("Album/Track%2.mp3",uri=true))
        assertNull(resolve("Album/Track\u0000 2.mp3"))
        assertNull(resolve("a".repeat(8193)))
    }
}
