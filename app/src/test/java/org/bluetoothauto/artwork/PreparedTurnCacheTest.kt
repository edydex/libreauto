// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.junit.Assert.*
import org.junit.Test

class PreparedTurnCacheTest {
    private data class Image(val number:Int)
    private fun key(turn:String,units:String="METRIC",style:String="style-v1")=PreparedTurnKey(turn,units,style)
    @Test fun fourthPreparedImageEvictsOldestAndStaysWithinThree(){
        val disposed=mutableListOf<Int>();val cache=PreparedTurnCache<Image>{disposed.add(it.number)}
        repeat(4){cache.put(key("turn-$it"),Image(it))}
        assertEquals(3,cache.size);assertEquals(listOf(0),disposed);assertNull(cache.peek(key("turn-0")))
    }
    @Test fun promotionTransfersOwnershipSoLaterEvictionCannotRecycleDisplayedImage(){
        val disposed=mutableListOf<Int>();val cache=PreparedTurnCache<Image>{disposed.add(it.number)}
        val next=Image(2);cache.put(key("future-B"),next)
        assertSame(next,cache.take(key("future-B")));assertEquals(0,cache.size)
        repeat(4){cache.put(key("later-$it"),Image(it+10))};cache.clear()
        assertFalse(2 in disposed)
    }
    @Test fun currentTurnAThenFutureBPromotionDoesNotRequireInstructionIdentity(){
        val cache=PreparedTurnCache<Image>{};val future=Image(2)
        cache.put(key("route-one:B"),future)
        assertNull(cache.take(key("route-one:A")))
        assertSame(future,cache.take(key("route-one:B")))
    }
    @Test fun rerouteClearDisposesFutureImagesAndCannotPromoteOldRoute(){
        val disposed=mutableListOf<Int>();val cache=PreparedTurnCache<Image>{disposed.add(it.number)}
        cache.put(key("old-route:B"),Image(2));cache.clear()
        assertNull(cache.take(key("old-route:B")));assertNull(cache.take(key("new-route:B")));assertEquals(listOf(2),disposed)
    }
    @Test fun unitsStyleAndGeometryAreIndependentCacheBoundaries(){
        val cache=PreparedTurnCache<Image>{};cache.put(key("B"),Image(1))
        assertNull(cache.take(key("C")));assertNull(cache.take(key("B","IMPERIAL")));assertNull(cache.take(key("B",style="style-v2")))
        assertNotNull(cache.take(key("B")))
    }
    @Test fun repeatedSessionCleanupDoesNotDisposeAnImageTwice(){
        val disposed=mutableListOf<Int>();val cache=PreparedTurnCache<Image>{disposed.add(it.number)}
        cache.put(key("B"),Image(1));cache.clear();cache.clear()
        assertEquals(listOf(1),disposed);assertEquals(0,cache.size)
    }
    @Test fun recentlyReadPreparedImageSurvivesLruEviction(){
        val cache=PreparedTurnCache<Image>{};repeat(3){cache.put(key("$it"),Image(it))}
        cache.peek(key("0"));cache.put(key("3"),Image(3))
        assertNotNull(cache.peek(key("0")));assertNull(cache.peek(key("1")))
    }
    @Test fun replacingSameKeyReleasesOnlyPreviousImage(){
        val disposed=mutableListOf<Int>();val cache=PreparedTurnCache<Image>{disposed.add(it.number)}
        cache.put(key("B"),Image(1));val replacement=Image(2);cache.put(key("B"),replacement);cache.put(key("B"),replacement)
        assertEquals(listOf(1),disposed);cache.clear();assertEquals(listOf(1,2),disposed)
    }
    @Test(expected=IllegalArgumentException::class) fun cannotAliasOneOwnedImageAcrossTwoEntries(){
        val cache=PreparedTurnCache<Image>{};val same=Image(1);cache.put(key("A"),same);cache.put(key("B"),same)
    }
    @Test(expected=IllegalArgumentException::class) fun cannotConfigureUnboundedImageCount(){PreparedTurnCache<Image>(4){}}
}
