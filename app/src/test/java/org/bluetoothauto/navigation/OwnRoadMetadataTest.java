// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class OwnRoadMetadataTest {
    private RoutePlan route() {
        List<GeoPoint> points = Arrays.asList(new GeoPoint(47, -122), new GeoPoint(47.001, -122), new GeoPoint(47.001, -121.999));
        return new RoutePlan(points, Arrays.asList(new RoutePlan.Maneuver(0, 1, "Head north", "", 1, 20),
                new RoutePlan.Maneuver(1, 2, "Turn right", "", 10, 10)), 30, "Destination");
    }
    private JSONObject response(RoutePlan route, JSONArray limits) throws Exception {
        return new JSONObject().put("shape", RoutePlan.encodePolyline6(route.points))
                .put("shape_attributes", new JSONObject().put("speed_limit", limits));
    }
    @Test public void usesPostedLimitsWithoutReplacingGeometryOrTiming() throws Exception {
        RoutePlan original = route();
        RoutePlan enriched = OwnMapProvider.parseRoadSpeedLimits(response(original, new JSONArray().put(40).put(25)).toString(), original);
        assertTrue(enriched.metadataEnriched);
        assertEquals(40, enriched.speedLimitKphAt(5), 0);
        assertEquals(25, enriched.speedLimitKphAt(enriched.cumulativeMeters[1] + 1), 0);
        assertEquals(original.seconds, enriched.seconds, 0);
        assertEquals(original.remainingSecondsAt(40), enriched.remainingSecondsAt(40), 0);
        assertSame(original.points.get(1), enriched.points.get(1));
        assertSame(original.maneuvers.get(1), enriched.maneuvers.get(1));
    }
    @Test public void shapeLimitsStayKilometersPerHourEvenWhenUnitsSaysMiles() throws Exception {
        RoutePlan original = route();
        JSONObject response = response(original, new JSONArray().put(50).put(60)).put("units", "miles");
        assertEquals(50, OwnMapProvider.parseRoadSpeedLimits(response.toString(), original).speedLimitKphAt(1), 0);
    }
    @Test public void zerosAndMissingAttributesAreUnknown() throws Exception {
        RoutePlan original = route();
        assertSame(original, OwnMapProvider.parseRoadSpeedLimits(response(original, new JSONArray().put(0).put(JSONObject.NULL)).toString(), original));
        assertSame(original, OwnMapProvider.parseRoadSpeedLimits(new JSONObject().put("shape", RoutePlan.encodePolyline6(original.points)).toString(), original));
    }
    @Test public void unlimitedHasNoInventedNumericRestriction() throws Exception {
        RoutePlan original = route();
        RoutePlan enriched = OwnMapProvider.parseRoadSpeedLimits(response(original, new JSONArray().put(255).put(0)).toString(), original);
        assertEquals(Double.POSITIVE_INFINITY, enriched.speedLimitKphAt(1), 0);
        assertTrue(Double.isNaN(enriched.speedLimitKphAt(enriched.cumulativeMeters[1] + 1)));
    }
    @Test public void malformedLimitsDoNotBecomeRoadSpeeds() throws Exception {
        RoutePlan original = route();
        assertSame(original, OwnMapProvider.parseRoadSpeedLimits(response(original, new JSONArray().put("35 mph").put(-3)).toString(), original));
    }
    @Test public void changedInteriorGeometryIsRejected() throws Exception {
        RoutePlan original = route();
        JSONObject response = response(original, new JSONArray().put(40).put(25)).put("shape", RoutePlan.encodePolyline6(Arrays.asList(
                original.points.get(0), new GeoPoint(47.001001, -122), original.points.get(2))));
        try { OwnMapProvider.parseRoadSpeedLimits(response.toString(), original); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void segmentArrayMismatchIsRejected() throws Exception {
        RoutePlan original = route();
        try { OwnMapProvider.parseRoadSpeedLimits(response(original, new JSONArray().put(40)).toString(), original); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
