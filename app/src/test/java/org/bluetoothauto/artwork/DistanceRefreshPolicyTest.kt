// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.junit.Assert.*
import org.junit.Test

class DistanceRefreshPolicyTest {
    @Test fun thresholdBucketsIncludeTheirBoundary() {
        for ((distance, artwork, location) in listOf(
            Triple(0.0, 1000L, 1000L), Triple(150.0, 1000L, 1000L),
            Triple(150.01, 2000L, 2000L), Triple(500.0, 2000L, 2000L),
            Triple(500.01, 5000L, 3000L))) {
            assertEquals(artwork, DistanceRefreshPolicy.intervalMillis(distance))
            assertEquals(location, DistanceRefreshPolicy.locationIntervalMillis(distance))
        }
    }
    @Test fun unavailableOrInvalidDistanceUsesConservativeCadence() {
        for (distance in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            assertEquals(5000L, DistanceRefreshPolicy.intervalMillis(distance))
            assertEquals(3000L, DistanceRefreshPolicy.locationIntervalMillis(distance))
        }
    }
    @Test fun repeatedFarUpdatesDoNotSlideTheScheduledDeadline() {
        val first = DistanceRefreshPolicy.dueAtMillis(10_100, 10_000, 1000.0, true)
        assertEquals(15_000L, first)
        val latest = DistanceRefreshPolicy.dueAtMillis(14_900, 10_000, 800.0, true, existingDueAt = first)
        assertEquals(15_000L, latest)
        assertEquals(15_000L, DistanceRefreshPolicy.dueAtMillis(15_000, 10_000, 790.0, true, existingDueAt = latest))
    }
    @Test fun approachingTheTurnAdvancesAnExistingFarTimer() {
        val far = DistanceRefreshPolicy.dueAtMillis(10_100, 10_000, 700.0, true)
        val approach = DistanceRefreshPolicy.dueAtMillis(10_500, 10_000, 400.0, true, existingDueAt = far)
        assertEquals(12_000L, approach)
        val near = DistanceRefreshPolicy.dueAtMillis(10_800, 10_000, 100.0, true, existingDueAt = approach)
        assertEquals(11_000L, near)
    }
    @Test fun fartherLatestDistanceDoesNotPostponeAnExistingNearTimer() {
        val near = DistanceRefreshPolicy.dueAtMillis(10_100, 10_000, 100.0, true)
        assertEquals(11_000L, near)
        assertEquals(11_000L, DistanceRefreshPolicy.dueAtMillis(10_500, 10_000, 600.0, true, existingDueAt = near))
        // Preserve the fired deadline until this latest far sample is actually rendered.
        assertEquals(11_000L, DistanceRefreshPolicy.dueAtMillis(11_000, 10_000, 600.0, true, existingDueAt = near))
    }
    @Test fun revertingToRenderedContentCancelsThePendingUpdate() {
        assertNull(DistanceRefreshPolicy.dueAtMillis(10_500, 10_000, 100.0, false, existingDueAt = 11_000))
    }
    @Test fun clearAndUserRefreshAreImmediateEvenWithoutOtherChanges() {
        assertEquals(10_500L, DistanceRefreshPolicy.dueAtMillis(10_500, 10_000, null, false, true, 15_000))
        assertEquals(10_500L, DistanceRefreshPolicy.dueAtMillis(10_500, 10_000, 900.0, true, true, 15_000))
    }
    @Test fun anOverduePendingUpdateConsumesTheLatestSampleNow() {
        assertEquals(15_200L, DistanceRefreshPolicy.dueAtMillis(15_200, 10_000, 900.0, true, existingDueAt = 15_000))
    }
    @Test fun aConsumedOldTimerCannotAccelerateANewRenderPeriod() {
        assertEquals(20_000L, DistanceRefreshPolicy.dueAtMillis(15_100, 15_000, 900.0, true, existingDueAt = 11_000))
    }
    @Test fun anUninitializedOrResetRenderClockDoesNotBlockUpdates() {
        assertEquals(10_000L, DistanceRefreshPolicy.dueAtMillis(10_000, -1, 900.0, true))
        assertEquals(10_000L, DistanceRefreshPolicy.dueAtMillis(10_000, 11_000, 900.0, true))
    }
    @Test fun deadlineArithmeticDoesNotWrapToAnImmediateNegativeTime() {
        assertEquals(Long.MAX_VALUE, DistanceRefreshPolicy.dueAtMillis(Long.MAX_VALUE - 100, Long.MAX_VALUE - 200, 900.0, true))
    }
}
