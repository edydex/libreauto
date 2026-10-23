// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteTrackerMetadataTest {
    private RoutePlan route() {
        return new RoutePlan(List.of(new GeoPoint(0, 0), new GeoPoint(0, .001),
                new GeoPoint(0, .002), new GeoPoint(0, .003)),
                List.of(new RoutePlan.Maneuver(0, 3, "Continue", "Continue", 8, 100)), 100, "Test");
    }
    private RoutePlan enriched(RoutePlan route) {
        return route.withRoadMetadata(new double[] {10, 70, 20}, new double[] {40, 50, 60});
    }
    @Test public void completedOffRouteDecisionAndAlongProgressSurviveMetadata() {
        RoutePlan route = route(); RouteTracker tracker = new RouteTracker(route);
        tracker.update(new GeoPoint(0, .0015), 5, 1000);
        GeoPoint away = new GeoPoint(.001, .0015);
        tracker.update(away, 5, 2000); tracker.update(away, 5, 3500);
        RouteTracker.Progress before = tracker.update(away, 5, 5500);
        assertTrue(before.offRoute); assertTrue(before.alongMeters > 100);
        RouteTracker.Progress after = tracker.updatePlan(enriched(route));
        assertTrue(after.offRoute); assertFalse(after.matched);
        assertEquals(before.alongMeters, after.alongMeters, 0);
        assertEquals(before.remainingMeters, after.remainingMeters, 0);
        assertEquals(before.maneuverIndex, after.maneuverIndex);
        assertTrue(Double.isNaN(after.speedLimitKph));
        assertNull(tracker.update(away, 5, 5500));
        RouteTracker.Progress next = tracker.update(away, 5, 6000);
        assertTrue(next.offRoute); assertEquals(before.alongMeters, next.alongMeters, 0);
    }
    @Test public void inProgressOffRouteHysteresisIsNotRestartedOrAdvancedByMetadata() {
        RoutePlan route = route(); RouteTracker tracker = new RouteTracker(route);
        tracker.update(new GeoPoint(0, .0015), 5, 1000);
        GeoPoint away = new GeoPoint(.001, .0015);
        tracker.update(away, 5, 2000);
        RouteTracker.Progress second = tracker.update(away, 5, 3500);
        assertFalse(second.offRoute);
        assertFalse(tracker.updatePlan(enriched(route)).offRoute);
        assertFalse(tracker.updatePlan(enriched(route)).offRoute);
        assertTrue(tracker.update(away, 5, 5500).offRoute);
    }
    @Test public void parkedMatchingFixGetsNewTimingAndLimitWithoutBeingObservedTwice() {
        RoutePlan route = route(); RouteTracker tracker = new RouteTracker(route);
        GeoPoint fix = new GeoPoint(0, .0015);
        RouteTracker.Progress before = tracker.update(fix, 5, 1000);
        assertTrue(before.matched); assertTrue(Double.isNaN(before.speedLimitKph));
        RouteTracker.Progress after = tracker.updatePlan(enriched(route));
        assertSame(before.snapped, after.snapped);
        assertEquals(before.alongMeters, after.alongMeters, 0);
        assertEquals(before.crossTrackMeters, after.crossTrackMeters, 0);
        assertEquals(before.maneuverIndex, after.maneuverIndex);
        assertEquals(50, after.speedLimitKph, .001);
        assertEquals(55, after.remainingSeconds, .001);
        assertNull(tracker.update(fix, 5, 1000));
        assertEquals(55, tracker.update(fix, 5, 1001).remainingSeconds, .001);
    }
    @Test public void mismatchedGeometryCannotChangeTheExistingTracker() {
        RoutePlan route = route(); RouteTracker tracker = new RouteTracker(route);
        GeoPoint fix = new GeoPoint(0, .0015);
        tracker.update(fix, 5, 1000);
        RoutePlan different = new RoutePlan(List.of(new GeoPoint(.000001, 0), route.points.get(1),
                route.points.get(2), route.points.get(3)), route.maneuvers, route.seconds, route.destinationName);
        assertThrows(IllegalArgumentException.class, () -> tracker.updatePlan(different));
        RouteTracker.Progress next = tracker.update(fix, 5, 1001);
        assertTrue(next.matched); assertTrue(Double.isNaN(next.speedLimitKph));
    }
    @Test public void refreshBeforeFirstFixDoesNotCreateAnObservationOrPermitNewGuidance() {
        RoutePlan route = route(); RouteTracker tracker = new RouteTracker(route);
        assertNull(tracker.updatePlan(enriched(route)));
        assertTrue(tracker.update(new GeoPoint(0, .0015), 5, 1000).matched);
        RoutePlan newGuidance = new RoutePlan(route.points,
                List.of(new RoutePlan.Maneuver(0, 3, "Turn", "Turn", 10, 100)), 100, "Test");
        assertThrows(IllegalArgumentException.class, () -> tracker.updatePlan(newGuidance));
        assertThrows(IllegalArgumentException.class, () -> tracker.updatePlan(route.withOffline()));
    }
}
