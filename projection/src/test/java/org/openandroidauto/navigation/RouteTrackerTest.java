package org.openandroidauto.navigation;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class RouteTrackerTest {
    private static RoutePlan straight() {
        List<GeoPoint> points = Arrays.asList(new GeoPoint(0, 0), new GeoPoint(0, .001),
                new GeoPoint(0, .002), new GeoPoint(0, .003));
        return new RoutePlan(points, Arrays.asList(
                new RoutePlan.Maneuver(0, 1, "Head east", "Head east", 1),
                new RoutePlan.Maneuver(1, 3, "Turn right", "Turn right", 10),
                new RoutePlan.Maneuver(3, 3, "Arrive", "Arrive", 4)), 60, "Test destination");
    }

    @Test public void progressesAlongGeometryAndChoosesUpcomingTurn() {
        RoutePlan route = straight();
        RouteTracker tracker = new RouteTracker(route);
        RouteTracker.Progress first = tracker.update(new GeoPoint(0, .0005), 5, 1000);
        assertEquals(55.6, first.alongMeters, .5);
        assertEquals(55.6, first.nextManeuverMeters, .5);
        assertEquals(1, first.maneuverIndex);
        assertEquals(278, first.remainingMeters, 1);
        assertFalse(first.offRoute);
        RouteTracker.Progress second = tracker.update(new GeoPoint(0, .0012), 5, 2000);
        assertEquals(2, second.maneuverIndex);
        assertTrue(second.alongMeters > first.alongMeters);
    }

    @Test public void needsThreeFixesAndThreeSecondsToReroute() {
        RouteTracker tracker = new RouteTracker(straight());
        tracker.update(new GeoPoint(0, 0), 5, 1000);
        assertFalse(tracker.update(new GeoPoint(.001, .0005), 5, 2000).offRoute);
        assertFalse(tracker.update(new GeoPoint(.001, .0006), 5, 3000).offRoute);
        assertTrue(tracker.update(new GeoPoint(.001, .0007), 5, 5000).offRoute);
        assertFalse(tracker.update(new GeoPoint(0, .0008), 5, 6000).offRoute);
    }

    @Test public void poorAccuracyDoesNotTriggerReroutingOrAdvance() {
        RouteTracker tracker = new RouteTracker(straight());
        RouteTracker.Progress before = tracker.update(new GeoPoint(0, .0005), 5, 1000);
        for (int i = 2; i <= 8; i++) {
            RouteTracker.Progress noisy = tracker.update(new GeoPoint(.002, .003), 90, i * 1000L);
            assertFalse(noisy.usableFix);
            assertFalse(noisy.offRoute);
            assertEquals(before.alongMeters, noisy.alongMeters, .1);
        }
    }

    @Test public void rejectsDuplicateAndOldLocationTimes() {
        RouteTracker tracker = new RouteTracker(straight());
        assertNotNull(tracker.update(new GeoPoint(0, 0), 5, 1000));
        assertNull(tracker.update(new GeoPoint(0, .001), 5, 1000));
        assertNull(tracker.update(new GeoPoint(0, .001), 5, 900));
    }

    @Test public void arrivalRequiresEndOfRouteAndPhysicalProximity() {
        RouteTracker tracker = new RouteTracker(straight());
        assertFalse(tracker.update(new GeoPoint(0, .001), 5, 1000).arrived);
        assertTrue(tracker.update(new GeoPoint(0, .00295), 5, 2000).arrived);
    }

    @Test public void routeCrossingDoesNotSkipToLaterBranch() {
        List<GeoPoint> points = Arrays.asList(new GeoPoint(0, -.001), new GeoPoint(0, 0),
                new GeoPoint(0, .001), new GeoPoint(.001, .001), new GeoPoint(.001, 0),
                new GeoPoint(0, 0), new GeoPoint(-.001, 0));
        RoutePlan route = new RoutePlan(points, Arrays.asList(
                new RoutePlan.Maneuver(0, 3, "Continue", "Continue", 1),
                new RoutePlan.Maneuver(3, 6, "Turn left", "Turn left", 15)), 120, "Crossing");
        RouteTracker tracker = new RouteTracker(route);
        tracker.update(new GeoPoint(0, -.0002), 5, 1000);
        RouteTracker.Progress intersection = tracker.update(new GeoPoint(0, 0), 5, 2000);
        assertEquals(111.2, intersection.alongMeters, 1);
        assertTrue(intersection.remainingMeters > 400);
    }

    @Test public void datelineSegmentUsesShortArc() {
        RoutePlan route = new RoutePlan(Arrays.asList(new GeoPoint(0, 179.999), new GeoPoint(0, -179.999)),
                Arrays.asList(new RoutePlan.Maneuver(0, 1, "East", "East", 1)), 30, "Dateline");
        RouteTracker.Progress progress = new RouteTracker(route).update(new GeoPoint(0, 180), 5, 1000);
        assertEquals(111.2, progress.alongMeters, 1);
        assertEquals(0, progress.crossTrackMeters, 1);
    }

    @Test public void reacquiresAfterLargeLegitimateLocationGap() {
        java.util.ArrayList<GeoPoint> points = new java.util.ArrayList<>();
        for (int index = 0; index <= 200; index++) points.add(new GeoPoint(0, index * .001));
        RoutePlan route = new RoutePlan(points, Arrays.asList(
                new RoutePlan.Maneuver(0, 200, "Continue east", "Continue east", 1)), 1800, "Long route");
        RouteTracker tracker = new RouteTracker(route);
        tracker.update(points.get(0), 5, 1000);
        RouteTracker.Progress recovered = tracker.update(points.get(150), 5, 1200000);
        assertEquals(route.cumulativeMeters[150], recovered.alongMeters, 2);
        assertFalse(recovered.offRoute);
    }

    @Test public void decodesSixDigitShapesIncludingNegativeCoordinates() {
        String shape = encode(47.60889, -122.33748, 47.62051, -122.35087);
        List<GeoPoint> points = RoutePlan.decodePolyline6(shape);
        assertEquals(2, points.size());
        assertEquals(47.60889, points.get(0).latitude, .0000001);
        assertEquals(-122.35087, points.get(1).longitude, .0000001);
    }

    @Test public void refusesMalformedPolylineAndManeuverIndexes() {
        for (String shape : Arrays.asList("_", "_?", "\n?", "~~~~~~~~~~~~?")) {
            try { RoutePlan.decodePolyline6(shape); fail("Accepted malformed shape"); }
            catch (IllegalArgumentException expected) {}
        }
        try {
            new RoutePlan(Arrays.asList(new GeoPoint(0, 0), new GeoPoint(0, 1)),
                    Arrays.asList(new RoutePlan.Maneuver(0, 5, "Bad", "Bad", 1)), 1, "Bad");
            fail("Accepted invalid maneuver");
        } catch (IllegalArgumentException expected) {}
    }

    static String encode(double... pairs) {
        StringBuilder out = new StringBuilder();
        long lastLat = 0, lastLon = 0;
        for (int index = 0; index < pairs.length; index += 2) {
            long latitude = Math.round(pairs[index] * 1000000), longitude = Math.round(pairs[index + 1] * 1000000);
            component(out, latitude - lastLat); component(out, longitude - lastLon);
            lastLat = latitude; lastLon = longitude;
        }
        return out.toString();
    }

    private static void component(StringBuilder out, long value) {
        value = value < 0 ? ~(value << 1) : value << 1;
        while (value >= 32) { out.append((char) ((32 | (value & 31)) + 63)); value >>= 5; }
        out.append((char) (value + 63));
    }
}
