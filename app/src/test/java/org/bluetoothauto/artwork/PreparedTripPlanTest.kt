// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.junit.Assert.*
import org.junit.Test

class PreparedTripPlanTest {
    private data class Input(val name: String)
    private fun key(name: String) = PreparedTurnKey(name, "METRIC", "style")
    private fun plan() = PreparedTripPlan<Input> { key(it.name) }

    @Test fun serialPollingPreservesAllRouteStepsBeyondThreeRamImages() {
        val plan=plan();plan.merge((0 until 14).map { Input("step-$it") })
        repeat(14) { index ->
            assertEquals("step-$index",plan.poll()?.name)
            plan.finish(key("step-$index"),true)
        }
        assertNull(plan.poll());assertEquals(14,plan.completedCount);assertEquals(0,plan.pendingCount)
    }
    @Test fun inFlightStepsCannotBeQueuedAgainByRepeatedDistanceUpdates() {
        val plan=plan();val items=listOf(Input("A"),Input("B"));plan.merge(items)
        assertEquals("A",plan.poll()?.name);repeat(1000){plan.merge(items)}
        assertEquals("B",plan.poll()?.name);assertNull(plan.poll());assertEquals(2,plan.pendingCount)
    }
    @Test fun cancelledFutureCanResumeAfterCurrentTurnPreemptsItsNativeSlot() {
        val plan=plan();plan.merge(listOf(Input("B"),Input("C")))
        assertEquals("B",plan.poll()?.name);plan.finish(key("B"),false)
        assertEquals("B",plan.poll()?.name);plan.finish(key("B"),true)
        assertEquals("C",plan.poll()?.name)
    }
    @Test fun savedDiskStepsStayCompleteWhenAdditionalGeometryBecomesProved() {
        val plan=plan();plan.merge(listOf(Input("A")));plan.poll();plan.finish(key("A"),true)
        plan.merge(listOf(Input("A"),Input("B"),Input("C")))
        assertEquals("B",plan.poll()?.name);assertEquals(3,plan.plannedCount);assertEquals(1,plan.completedCount)
    }
    @Test fun duplicateStepKeysDoNotGrowThePlanOrReplaceTheOriginalInput() {
        val plan=plan();plan.merge(List(1000){Input("A")})
        assertEquals(1,plan.plannedCount);assertEquals("A",plan.poll()?.name);assertNull(plan.poll())
    }
    @Test fun completeTripInputCountIsBoundedAt256() {
        val plan=plan();plan.merge((0 until 1000).map { Input("$it") })
        assertEquals(256,plan.plannedCount);assertEquals(256,plan.pendingCount)
    }
    @Test fun staleCompletionCannotResurrectAReroutedClearedPlan() {
        val plan=plan();plan.merge(listOf(Input("old")));plan.poll();plan.clear()
        plan.merge(listOf(Input("new")));plan.finish(key("old"),true)
        assertEquals(0,plan.completedCount);assertEquals(1,plan.pendingCount);assertEquals("new",plan.poll()?.name)
    }
    @Test fun stoppingAndClearingIsIdempotentAndReleasesLightweightInputs() {
        val plan=plan();plan.merge(listOf(Input("A"),Input("B")));plan.poll();plan.clear();plan.clear()
        assertEquals(0,plan.plannedCount);assertEquals(0,plan.completedCount);assertEquals(0,plan.pendingCount);assertNull(plan.poll())
    }
    @Test fun budgetEvictionIsNotReportedCompleteAndDoesNotCauseAnEndlessPreloadLoop() {
        val plan=plan();plan.merge(listOf(Input("A"),Input("B")));plan.poll();plan.finish(key("A"),true)
        plan.forgetSaved(key("A"));assertEquals(0,plan.completedCount);assertEquals(2,plan.pendingCount)
        assertEquals("B",plan.poll()?.name);plan.finish(key("B"),true)
        assertFalse(plan.hasWork);assertNull(plan.poll());assertEquals(1,plan.pendingCount)
    }
    @Test fun actualCurrentTurnCanRestoreAnEvictedImageAndCompleteItAgain() {
        val plan=plan();plan.merge(listOf(Input("A")));plan.poll();plan.finish(key("A"),true);plan.forgetSaved(key("A"))
        plan.finish(key("A"),true);assertEquals(1,plan.completedCount);assertEquals(0,plan.pendingCount)
    }
    @Test fun enrichedFullListReplacesOldGeometryWithoutCountingTwoPicturesForOneStep() {
        val plan=plan();plan.reconcile(listOf(Input("fixed-current"),Input("future-old")))
        plan.poll();plan.finish(key("fixed-current"),true);plan.poll()
        assertEquals(setOf(key("future-old")),plan.reconcile(listOf(Input("fixed-current"),Input("future-new"))))
        assertEquals(2,plan.plannedCount);assertEquals(1,plan.completedCount);assertEquals("future-new",plan.poll()?.name)
        plan.finish(key("future-old"),true);assertEquals(1,plan.completedCount)
    }
    @Test fun reconciliationRetainsCommonInFlightAndCompletedWorkWithoutRequeuingIt() {
        val plan=plan();val a=Input("A");plan.reconcile(listOf(a,Input("B")))
        plan.poll();plan.finish(key("A"),true);plan.poll()
        assertTrue(plan.reconcile(listOf(Input("A"),Input("B"),Input("C"))).isEmpty())
        assertSame(a,plan.value(key("A")));assertEquals("C",plan.poll()?.name)
        plan.finish(key("B"),true);assertEquals(2,plan.completedCount)
    }
    @Test fun emptyFullPlanReconciliationCancelsAllProofWithoutAcceptingLateCompletions() {
        val plan=plan();plan.reconcile(listOf(Input("A")));plan.poll()
        assertEquals(setOf(key("A")),plan.reconcile(emptyList()));plan.finish(key("A"),true)
        assertEquals(0,plan.plannedCount);assertEquals(0,plan.completedCount);assertNull(plan.poll())
    }
    @Test fun changedEarlierGeometryRunsBeforeRetainedLaterQueuedSteps() {
        val plan=plan();plan.reconcile(listOf(Input("A"),Input("B-old"),Input("C"),Input("D")))
        plan.poll();plan.finish(key("A"),true)
        assertEquals(listOf("B-old","C","D"),List(3){plan.poll()!!.name})
        plan.reconcile(listOf(Input("A"),Input("B-new"),Input("C"),Input("D")))
        // The renderer releases only queued trip-owned C/D before filling from the new route order.
        listOf("C","D").forEach{plan.finish(key(it),false)}
        assertEquals(listOf("B-new","C","D"),List(3){plan.poll()!!.name})
        assertEquals(1,plan.completedCount);assertNull(plan.poll())
    }
    @Test fun releasingQueuedWorkDoesNotRestartCurrentNativeJobOrSavedImages() {
        val plan=plan();plan.reconcile(listOf(Input("saved"),Input("native-active"),Input("C")))
        plan.poll();plan.finish(key("saved"),true);plan.poll();plan.poll()
        plan.reconcile(listOf(Input("saved"),Input("native-active"),Input("B-new"),Input("C")))
        plan.finish(key("C"),false)
        assertEquals("B-new",plan.poll()?.name);assertEquals("C",plan.poll()?.name)
        assertNull(plan.poll());assertEquals(1,plan.completedCount)
        plan.finish(key("native-active"),true);assertEquals(2,plan.completedCount)
    }
}
