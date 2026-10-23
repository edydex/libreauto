// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.junit.Assert.*
import org.junit.Test

class PlaylistParserTest {
    private fun parse(text: String, name: String = "queue.m3u8") = PlaylistParser.parse(text.toByteArray(), name)
    private fun xspf(tracks: String, attributes: String = "") =
        """<playlist version="1" xmlns="http://xspf.org/ns/0/" $attributes><trackList>$tracks</trackList></playlist>"""
    private fun track(location: String, extras: String = "") = "<track><location>$location</location>$extras</track>"

    @Test fun m3uPreservesOrderAndDuplicateTracks() {
        val result = parse("b.mp3\na.mp3\nb.mp3")
        assertEquals(listOf("b.mp3", "a.mp3", "b.mp3"), result.entries.map { it.location })
    }
    @Test fun utf8BomCrLfAndUnicodeAreSupported() {
        val result = parse("\uFEFF#EXTM3U\r\n#EXTINF:42,Привет 🦊\r\nПесня + 1.mp3\r\n")
        assertEquals(PlaylistEntry("Песня + 1.mp3", "Привет 🦊"), result.entries.single())
    }
    @Test fun m3uLiteralPercentAndPlusAreNotUrlDecoded() {
        assertEquals("100%20real+song.mp3", parse("100%20real+song.mp3").entries.single().location)
    }
    @Test fun m3uRelativeParentsRemainForAuthorizedTreeResolver() {
        assertEquals("../Album/song.mp3", parse("../Album/song.mp3").entries.single().location)
    }
    @Test fun networkAndUnknownSchemesNeverBecomeTracks() {
        val result = parse("https://example.com/a.mp3\nrtsp://example.com/a\ndata:audio/x\n//server/a.mp3\n\\\\server\\a.mp3\nlocal.mp3")
        assertEquals(listOf("local.mp3"), result.entries.map { it.location })
        assertEquals(5, result.skipped)
    }
    @Test fun nativeOptionsAndCommentsAreIgnored() {
        val result = parse("#EXTM3U\n#EXTVLCOPT:input-slave=https://example.com\n#comment\n#EXTINF:-1,First\na.mp3\nb.mp3")
        assertEquals("First", result.entries.first().title)
        assertNull(result.entries.last().title)
    }
    @Test fun invalidEntryConsumesItsPendingTitle() {
        val result = parse("#EXTINF:10,Wrong\nhttps://example.com/a\nb.mp3")
        assertNull(result.entries.single().title)
    }
    @Test fun hlsIsNotImportedAsALocalQueue() {
        assertTrue(parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\na.ts").entries.isEmpty())
    }
    @Test fun legacyM3uLatin1HasExplicitFallback() {
        val bytes = "caf\u00e9.mp3".toByteArray(Charsets.ISO_8859_1)
        assertEquals("café.mp3", PlaylistParser.parse(bytes, "queue.m3u").entries.single().location)
        assertTrue(PlaylistParser.parse(bytes, "queue.m3u8").entries.isEmpty())
    }
    @Test fun windowsPathsAreNotMistakenForNetworkSchemes() {
        assertEquals("C:\\Music\\a.mp3", parse("C:\\Music\\a.mp3").entries.single().location)
    }
    @Test fun remoteFileAuthorityIsNotLocal() {
        val result = parse("file://remote/a.mp3\nfile:///Music/a.mp3\ncontent://documents/a")
        assertEquals(2, result.entries.size)
        assertEquals(1, result.skipped)
    }
    @Test fun locationAndInputBoundsAreEnforced() {
        assertEquals(1, parse("a".repeat(8193)).skipped)
        val tooLarge = PlaylistParser.parse(ByteArray(PlaylistParser.MAX_BYTES + 1), "q.m3u8")
        assertTrue(tooLarge.truncated)
        assertTrue(tooLarge.entries.isEmpty())
    }
    @Test fun entryBoundDoesNotWrapOrReorder() {
        val result = parse((0..512).joinToString("\n") { "$it.mp3" })
        assertEquals(512, result.entries.size)
        assertEquals("511.mp3", result.entries.last().location)
        assertTrue(result.truncated)
    }
    @Test fun xspfPreservesTrackOrderAndMetadata() {
        val result = parse(xspf(track("b.mp3", "<title>B &amp; B</title><creator>Artist</creator>") + track("a.mp3") + track("b.mp3")), "q.xspf")
        assertEquals(listOf("b.mp3", "a.mp3", "b.mp3"), result.entries.map { it.location })
        assertEquals("B & B", result.entries.first().title)
        assertEquals("Artist", result.entries.first().artist)
    }
    @Test fun xspfUriEscapesRemainEncodedExactlyOnce() {
        val result = parse(xspf(track("Folder/My%20Song+%2520%23.mp3")), "q.xspf")
        assertEquals("Folder/My%20Song+%2520%23.mp3", result.entries.single().location)
    }
    @Test fun xspfXmlEntitiesAreDecodedBeforeUriResolution() {
        assertEquals("a&amp;b.mp3", parse(xspf(track("a&amp;amp;b.mp3")), "q.xspf").entries.single().location)
    }
    @Test fun xspfRelativeXmlBaseIsAppliedWithoutPercentDecoding() {
        val result = parse(xspf(track("My%20Song.mp3"), "xml:base=\"Album/\""), "q.xspf")
        assertEquals("Album/My%20Song.mp3", result.entries.single().location)
    }
    @Test fun xspfNestedXmlBaseUsesTravelOrder() {
        val xml = """<playlist xmlns="http://xspf.org/ns/0/" xml:base="Music/"><trackList><track xml:base="Album/"><location xml:base="Disc/">song.mp3</location></track></trackList></playlist>"""
        assertEquals("Music/Album/Disc/song.mp3", parse(xml, "q.xspf").entries.single().location)
    }
    @Test fun malformedXmlBaseIsNeverSilentlyIgnored() {
        assertTrue(parse(xspf(track("song.mp3"), "xml:base=\"bad base/\""), "q.xspf").entries.isEmpty())
    }
    @Test fun xspfSupportsNamespacePrefixes() {
        val xml = """<x:playlist xmlns:x="http://xspf.org/ns/0/"><x:trackList><x:track><x:location>a.mp3</x:location></x:track></x:trackList></x:playlist>"""
        assertEquals("a.mp3", parse(xml, "q.xspf").entries.single().location)
    }
    @Test fun xspfChoosesOneLocalAlternativePerTrack() {
        val xml = xspf("<track><location>https://example.com/a.mp3</location><location>a.mp3</location><location>b.mp3</location></track>")
        assertEquals(listOf("a.mp3"), parse(xml, "q.xspf").entries.map { it.location })
    }
    @Test fun xspfMissingOrUnsupportedTracksAreSkipped() {
        val result = parse(xspf("<track><title>No location</title></track>" + track("https://example.com/a") + track("ok.mp3")), "q.xspf")
        assertEquals(2, result.skipped)
        assertEquals("ok.mp3", result.entries.single().location)
    }
    @Test fun xspfDoesNotTreatForeignExtensionLocationsAsTracks() {
        val xml = """<playlist xmlns="http://xspf.org/ns/0/" xmlns:z="urn:other"><extension><z:track><z:location>wrong.mp3</z:location></z:track></extension><trackList><track><z:location>wrong.mp3</z:location><location>right.mp3</location></track></trackList></playlist>"""
        assertEquals("right.mp3", parse(xml, "q.xspf").entries.single().location)
    }
    @Test fun xspfDoctypeAndEntitiesAreRejectedWithoutFetching() {
        val xml = """<!DOCTYPE playlist SYSTEM "https://example.com/external.dtd">""" + xspf(track("a.mp3"))
        assertTrue(parse(xml, "q.xspf").entries.isEmpty())
        val entity = """<!DOCTYPE playlist [<!ENTITY value SYSTEM "file:///not-a-real-fixture">]>""" + xspf(track("&value;"))
        assertTrue(parse(entity, "q.xspf").entries.isEmpty())
    }
    @Test fun utf16XmlCannotHideDoctype() {
        val xml = "\uFEFF<!DOCTYPE playlist SYSTEM \"https://example.com/a\">" + xspf(track("a.mp3"))
        assertTrue(PlaylistParser.parse(xml.toByteArray(Charsets.UTF_16LE), "q.xspf").entries.isEmpty())
    }
    @Test fun validUtf16XmlIsSupported() {
        val xml = "\uFEFF" + xspf(track("song.mp3"))
        assertEquals("song.mp3", PlaylistParser.parse(xml.toByteArray(Charsets.UTF_16BE), "q.xspf").entries.single().location)
    }
    @Test fun malformedXmlDoesNotReturnAPartialQueue() {
        assertTrue(parse(xspf(track("a.mp3")).dropLast(5), "q.xspf").entries.isEmpty())
    }
    @Test fun xmlNestingAndLocationAreBounded() {
        val nested = "<playlist>" + "<node>".repeat(65) + "</node>".repeat(65) + "</playlist>"
        assertTrue(parse(nested, "q.xspf").entries.isEmpty())
        assertEquals(1, parse(xspf(track("a".repeat(8193))), "q.xspf").skipped)
    }
    @Test fun plsUsesNumericOrderAndPreservesDuplicates() {
        val result = parse("[playlist]\nFile10=b.mp3\nTitle10=B\nFile2=a.mp3\nFile3=a.mp3\nNumberOfEntries=3", "q.pls")
        assertEquals(listOf("a.mp3", "a.mp3", "b.mp3"), result.entries.map { it.location })
        assertEquals("B", result.entries.last().title)
    }
    @Test fun metadataIsBoundedAndControlFree() {
        val result = parse("#EXTINF:3," + "a".repeat(200) + "\u0000\nb.mp3")
        assertEquals(180, result.entries.single().title?.length)
    }
    @Test fun emptyPlaylistsAreSafe() {
        assertTrue(PlaylistParser.parse(byteArrayOf(), "q.m3u8").entries.isEmpty())
        assertTrue(parse(xspf(""), "q.xspf").entries.isEmpty())
    }
}
