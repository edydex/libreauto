// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class PreparedTripImageStoreTest {
    @get:Rule val temporary=TemporaryFolder()
    private var now=1_000_000_000L
    private fun directory()=File(temporary.root,"images")
    private fun store(entries:Int=256,bytes:Long=PreparedTripImageStore.MAX_TOTAL_BYTES)=PreparedTripImageStore(directory(),{now},entries,bytes)
    private fun key(trip:String="route-one",geometry:String="step-one",units:String="METRIC",style:String="style-v1")=
        PreparedTripImageKey(trip,PreparedTurnKey(geometry,units,style))
    private fun image(width:Int=512,height:Int=512):ByteArray {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type:String,payload:ByteArray) {
            val name=type.toByteArray(Charsets.US_ASCII);out.writeInt(payload.size);out.write(name);out.write(payload)
            val crc=CRC32();crc.update(name);crc.update(payload);out.writeInt(crc.value.toInt())
        }
        val header=ByteArrayOutputStream();DataOutputStream(header).use{it.writeInt(width);it.writeInt(height);it.write(byteArrayOf(8,6,0,0,0))};chunk("IHDR",header.toByteArray())
        val raw=ByteArrayOutputStream();DeflaterOutputStream(raw).use{z->repeat(height){z.write(0);z.write(ByteArray(width*4){127})}};chunk("IDAT",raw.toByteArray());chunk("IEND",byteArrayOf())
        return bytes.toByteArray()
    }
    @Test fun savesAndRestoresLosslessBackgroundAfterProcessLocalStoreRecreation() {
        val source=image();store().apply{activate("route-one");assertTrue(save(key(),source))}
        val restored=store().apply{activate("route-one")}.load(key())
        assertArrayEquals(source,restored)
    }
    @Test fun inactiveOrDifferentTripCannotReadOrWriteImages() {
        val store=store();assertNull(store.load(key()));assertFalse(store.save(key(),image()))
        store.activate("route-two");assertFalse(store.save(key(),image()));assertNull(store.load(key()))
    }
    @Test fun rerouteActivationDeletesPriorNamespaceAndCannotPromoteItsGeometry() {
        val store=store();store.activate("route-one");store.save(key(),image())
        store.activate("route-two");assertNull(store.load(key()));assertEquals(0,directory().listFiles()!!.size)
        assertTrue(store.save(key(trip="route-two"),image()))
    }
    @Test fun geometryUnitsAndStyleAreIndependentImageBoundaries() {
        val store=store();store.activate("route-one");store.save(key(),image())
        assertNull(store.load(key(geometry="step-two")));assertNull(store.load(key(units="IMPERIAL")));assertNull(store.load(key(style="style-v2")))
    }
    @Test fun keyEncodingCannotAliasValuesWithTheSameUnseparatedConcatenation() {
        val a=key(geometry="ab",units="c");val b=key(geometry="a",units="bc")
        assertNotEquals(a.imageHash,b.imageHash)
    }
    @Test fun filenamesContainOnlyHashesNotInstructionsOrCoordinates() {
        val store=store();store.activate("private route wording");store.save(key(trip="private route wording",geometry="47.6062 private street"),image())
        assertTrue(directory().listFiles()!!.single().name.matches(Regex("[a-f0-9]{64}-[a-f0-9]{64}\\.map")))
    }
    @Test fun imageExpiresAfterSevenDaysEvenWhenReadRecently() {
        val store=store();store.activate("route-one");store.save(key(),image());now+=PreparedTripImageStore.MAX_AGE_MS
        assertNotNull(store.load(key()));now++;assertNull(store.load(key()));assertEquals(0,directory().listFiles()!!.size)
    }
    @Test fun corruptContentIsRemovedInsteadOfBeingReturned() {
        val store=store();store.activate("route-one");store.save(key(),image());val file=directory().listFiles()!!.single()
        val b=file.readBytes();b[b.lastIndex]=(b.last().toInt() xor 1).toByte();file.writeBytes(b)
        assertNull(store.load(key()));assertFalse(file.exists())
    }
    @Test fun changingFilenameCannotMakeAnImageMatchAnotherStep() {
        val store=store();store.activate("route-one");store.save(key(),image());val file=directory().listFiles()!!.single()
        val other=key(geometry="other");val renamed=File(directory(),"${other.tripHash}-${other.imageHash}.map");assertTrue(file.renameTo(renamed))
        assertNull(store.load(other));assertFalse(renamed.exists())
    }
    @Test fun incompleteOrOversizedCacheFilesAreRemoved() {
        val store=store();store.activate("route-one");store.save(key(),image());val file=directory().listFiles()!!.single();file.writeBytes(byteArrayOf(1,2,3))
        assertNull(store.load(key()));assertFalse(file.exists())
    }
    @Test fun rejectsNon512ImagesBeforeWriting() {
        val store=store();store.activate("route-one")
        assertThrows(IllegalArgumentException::class.java){store.save(key(),image(256,256))}
        assertThrows(IllegalArgumentException::class.java){store.save(key(),byteArrayOf(1,2,3))}
    }
    @Test fun rejectsEncodedImagesAboveTheHardAllocationLimit() {
        val store=store();store.activate("route-one")
        assertThrows(IllegalArgumentException::class.java){store.save(key(),ByteArray(PreparedTripImageStore.MAX_IMAGE_BYTES+1))}
    }
    @Test fun entryBoundEvictsLeastRecentlyUsedRatherThanMostRecentTurn() {
        val store=store(entries=2);store.activate("route-one");store.save(key(geometry="A"),image());now++
        store.save(key(geometry="B"),image());now++;assertNotNull(store.load(key(geometry="A")));now++
        store.save(key(geometry="C"),image())
        assertNotNull(store.load(key(geometry="A")));assertNull(store.load(key(geometry="B")));assertNotNull(store.load(key(geometry="C")))
    }
    @Test fun byteBoundPreventsTheCacheExceedingItsConfiguredBudget() {
        val source=image();val store=store(bytes=(source.size+84L)*2);store.activate("route-one")
        repeat(3){store.save(key(geometry="$it"),source);now++}
        assertTrue(directory().listFiles()!!.sumOf{it.length()}<=(source.size+84L)*2);assertEquals(2,directory().listFiles()!!.size)
    }
    @Test fun imageTooLargeForConfiguredBudgetIsNotPartiallyWritten() {
        val store=store(bytes=1);store.activate("route-one");assertFalse(store.save(key(),image()));assertFalse(directory().exists())
    }
    @Test fun clearRemovesOnlyOwnedFilesAndPreservesUnrelatedPrivateState() {
        val store=store();store.activate("route-one");store.save(key(),image());val unrelated=File(directory(),"other-settings.json").apply{writeText("keep")}
        store.clear();store.clear();assertEquals("keep",unrelated.readText());assertNull(store.load(key()))
    }
    @Test fun interruptedWriteNeverPublishesANewImage() {
        val store=store();store.activate("route-one");Thread.currentThread().interrupt()
        try{assertThrows(InterruptedException::class.java){store.save(key(),image())}}
        finally{Thread.interrupted()}
        assertNull(store.load(key()))
    }
    @Test fun evictionReportsTheRemovedDigestSoProgressCannotClaimEveryImageIsStillStored() {
        val store=store(entries=1);store.activate("route-one");store.save(key(geometry="A"),image());now++
        val result=store.saveResult(key(geometry="B"),image())
        assertTrue(result.saved);assertEquals(setOf(key(geometry="A").imageHash),result.evictedImageHashes)
    }
}
