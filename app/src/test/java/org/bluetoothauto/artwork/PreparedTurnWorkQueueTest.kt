// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.junit.Assert.*
import org.junit.Test

class PreparedTurnWorkQueueTest {
    private data class Work(val turn:String,val attempt:Int=1)
    private fun key(turn:String)=PreparedTurnKey(turn,"METRIC","style")
    private fun queue()=PreparedTurnWorkQueue<Work>{key(it.turn)}

    @Test fun twoFutureTurnsArePreparedSeriallyInLookaheadOrder(){
        val queue=queue();queue.offer(Work("B"));queue.offer(Work("C"))
        assertEquals("B",queue.poll()?.turn);assertEquals("C",queue.poll()?.turn);assertNull(queue.poll())
    }
    @Test fun queueRejectsFourthFutureWithoutEvictingNearestTurn(){
        val queue=queue();listOf("B","C","D").forEach{assertTrue(queue.offer(Work(it)))}
        assertFalse(queue.offer(Work("E")));assertEquals(3,queue.size)
        assertEquals(listOf("B","C","D"),queue.clearAndTake().map{it.turn})
    }
    @Test fun sameFutureKeyCannotGrowAnUnboundedWorkQueue(){
        val queue=queue();assertTrue(queue.offer(Work("B")))
        repeat(1000){assertFalse(queue.offer(Work("B",it+2)))}
        assertEquals(1,queue.size);assertEquals(1,queue.poll()?.attempt)
    }
    @Test fun visibleTurnCanTakeItsQueuedWorkWithoutReorderingOtherFutureTurns(){
        val queue=queue();listOf("B","C","D").forEach{queue.offer(Work(it))}
        assertEquals("C",queue.take(key("C"))?.turn)
        assertEquals(listOf("B","D"),queue.clearAndTake().map{it.turn})
    }
    @Test fun preemptedFutureReturnsToFrontAfterVisibleTurnGetsNativeSlot(){
        val queue=queue();queue.offer(Work("B"));queue.offer(Work("C"))
        val preempted=queue.poll()!!
        assertTrue(queue.offer(preempted,first=true));assertSame(preempted,queue.poll())
        assertEquals("C",queue.poll()?.turn)
    }
    @Test fun rerouteClearReturnsEveryCancelledRequestAndLeavesNoFutureWork(){
        val queue=queue();queue.offer(Work("old-B"));queue.offer(Work("old-C"))
        assertEquals(listOf("old-B","old-C"),queue.clearAndTake().map{it.turn})
        assertTrue(queue.isEmpty());assertNull(queue.take(key("old-B")))
        assertTrue(queue.offer(Work("new-B")));assertEquals("new-B",queue.poll()?.turn)
    }
    @Test fun enrichedPlanRemovesOnlyObsoleteFutureJobsAndKeepsCommonOrder(){
        val queue=queue();listOf("fixed-B","old-C","fixed-D").forEach{queue.offer(Work(it))}
        assertEquals(listOf("old-C"),queue.removeKeys(setOf(key("old-C"))).map{it.turn})
        assertEquals(listOf("fixed-B","fixed-D"),queue.clearAndTake().map{it.turn})
    }
    @Test fun extractingTripWorkPreservesIndependentQueuedWorkAndObjectIdentity(){
        val queue=queue();val trip=Work("trip-C");val independent=Work("own-next")
        queue.offer(trip);queue.offer(independent)
        assertSame(trip,queue.removeMatching{it.turn.startsWith("trip-")}.single())
        assertSame(independent,queue.poll());assertTrue(queue.isEmpty())
    }
}
