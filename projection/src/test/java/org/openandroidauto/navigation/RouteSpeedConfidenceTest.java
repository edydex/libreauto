// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class RouteSpeedConfidenceTest {
    private RoutePlan straight() {
        return new RoutePlan(Arrays.asList(new GeoPoint(0, 0), new GeoPoint(0, .001), new GeoPoint(0, .002)),
                List.of(new RoutePlan.Maneuver(0, 2, "Continue", "Continue", 8, 100)), 100, "Test")
                .withRoadMetadata(new double[] {20, 80}, new double[] {40, 60});
    }
    @Test public void knownLimitAndRemainingTimeFollowTheMatchedSegment() {
        RoutePlan route = straight(); RouteTracker tracker = new RouteTracker(route);
        RouteTracker.Progress first = tracker.update(new GeoPoint(0, .0005), 5, 1000, 90f);
        assertTrue(first.matched); assertEquals(40, first.speedLimitKph, .001); assertEquals(90, first.remainingSeconds, .001);
        RouteTracker.Progress second = tracker.update(new GeoPoint(0, .0015), 5, 2000, 90f);
        assertTrue(second.matched); assertEquals(60, second.speedLimitKph, .001); assertEquals(40, second.remainingSeconds, .001);
    }
    @Test public void inaccurateGpsOrWrongRoadHidesLimitImmediatelyBeforeRerouteThreshold() {
        for (double[] fix : new double[][] {{0, .0005, 25}, {.0002, .0005, 5}, {.001, .0005, 5}}) {
            RouteTracker.Progress progress = new RouteTracker(straight()).update(new GeoPoint(fix[0], fix[1]), fix[2], 1000);
            assertFalse(progress.matched); assertTrue(Double.isNaN(progress.speedLimitKph));
            assertTrue(Double.isNaN(progress.remainingSeconds)); assertFalse(progress.offRoute);
        }
    }
    @Test public void oppositeMovingCourseCannotClaimTheRoutesSpeedLimit() {
        RouteTracker.Progress progress = new RouteTracker(straight()).update(new GeoPoint(0, .0005), 5, 1000, 270f);
        assertFalse(progress.matched); assertTrue(Double.isNaN(progress.speedLimitKph));
    }
    @Test public void stationaryWithoutCourseCanMatchButInvalidCourseCannot() {
        assertTrue(new RouteTracker(straight()).update(new GeoPoint(0, .0005), 5, 1000, null).matched);
        for (float bearing : new float[] {Float.NaN, -1, 360, Float.POSITIVE_INFINITY})
            assertFalse(new RouteTracker(straight()).update(new GeoPoint(0, .0005), 5, 1000, bearing).matched);
    }
    @Test public void nearbyParallelReturnRoadSuppressesAnAmbiguousLimit() {
        List<GeoPoint> points = Arrays.asList(new GeoPoint(0, 0), new GeoPoint(0, .002),
                new GeoPoint(.00005, .002), new GeoPoint(.00005, 0));
        RoutePlan route = new RoutePlan(points, List.of(new RoutePlan.Maneuver(0, 3, "Loop", "Loop", 8, 100)), 100, "Loop")
                .withRoadMetadata(new double[] {40, 20, 40}, new double[] {40, 20, 60});
        RouteTracker.Progress progress = new RouteTracker(route).update(new GeoPoint(.000025, .001), 5, 1000);
        assertFalse(progress.matched); assertTrue(Double.isNaN(progress.speedLimitKph));
    }
    @Test public void arrivalHasZeroRemainingTime() {
        RouteTracker.Progress progress = new RouteTracker(straight()).update(new GeoPoint(0, .00199), 5, 1000);
        assertTrue(progress.arrived); assertEquals(0, progress.remainingSeconds, 0);
    }
    @Test public void unknownLimitStaysUnknownEvenWithAConfidentTimedRoute() {
        RoutePlan route = new RoutePlan(straight().points,
                List.of(new RoutePlan.Maneuver(0, 2, "Continue", "Continue", 8, 100)), 100, "No posted data");
        RouteTracker.Progress progress = new RouteTracker(route).update(new GeoPoint(0, .0005), 5, 1000);
        assertTrue(progress.matched); assertTrue(Double.isNaN(progress.speedLimitKph)); assertEquals(75, progress.remainingSeconds, .001);
    }
    @Test public void aLargeBackwardJumpDoesNotUseTheOldForwardSegmentsLimit() {
        RouteTracker tracker = new RouteTracker(straight());
        tracker.update(new GeoPoint(0, .0015), 5, 1000);
        RouteTracker.Progress backwards = tracker.update(new GeoPoint(0, .0002), 5, 2000);
        assertFalse(backwards.matched); assertTrue(Double.isNaN(backwards.speedLimitKph));
    }
}
