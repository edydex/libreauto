// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class OwnMapProviderTest {
    @Test public void candidateCorrelationIncludesTheMovingCourseWithoutChangingOrdinaryRouting() throws Exception {
        GeoPoint origin = new GeoPoint(47.6062, -122.3321);
        OwnMapProvider.Place destination = new OwnMapProvider.Place("Fixture", new GeoPoint(47.61, -122.33));
        JSONObject candidate = new JSONObject(OwnMapProvider.routeRequest(origin, destination, 359.8f, true))
                .getJSONArray("locations").getJSONObject(0);
        assertEquals(0, candidate.getInt("heading"));
        assertEquals(40, candidate.getInt("heading_tolerance"));
        assertEquals(25, candidate.getInt("radius"));
        assertFalse(candidate.getBoolean("rank_candidates"));
        JSONObject ordinary = new JSONObject(OwnMapProvider.routeRequest(origin, destination, 234.75f, false))
                .getJSONArray("locations").getJSONObject(0);
        assertEquals(234.75, ordinary.getDouble("heading"), .0001);
        assertFalse(ordinary.has("heading_tolerance"));assertFalse(ordinary.has("radius"));
        assertFalse(ordinary.has("rank_candidates"));
        JSONObject unknown = new JSONObject(OwnMapProvider.routeRequest(origin, destination, null, true))
                .getJSONArray("locations").getJSONObject(0);
        assertFalse(unknown.has("heading"));assertFalse(unknown.has("radius"));
    }
    private JSONObject route() throws Exception {
        List<GeoPoint> points = Arrays.asList(new GeoPoint(47.6062, -122.3321),
                new GeoPoint(47.6062, -122.3311), new GeoPoint(47.6052, -122.3311));
        JSONArray maneuvers = new JSONArray()
                .put(new JSONObject().put("begin_shape_index", 0).put("end_shape_index", 1)
                        .put("instruction", "Head east").put("type", 1).put("time", 20))
                .put(new JSONObject().put("begin_shape_index", 1).put("end_shape_index", 2)
                        .put("instruction", "Turn right onto Test Street").put("type", 10).put("time", 40))
                .put(new JSONObject().put("begin_shape_index", 2).put("end_shape_index", 2)
                        .put("instruction", "You have arrived").put("type", 4).put("time", 0));
        return new JSONObject().put("trip", new JSONObject().put("status", 0)
                .put("summary", new JSONObject().put("time", 60))
                .put("legs", new JSONArray().put(new JSONObject().put("shape", RoutePlan.encodePolyline6(points)).put("maneuvers", maneuvers))));
    }
    @Test public void parsesRealDrivingShapeInstructionsAndProviderTiming() throws Exception {
        RoutePlan plan = OwnMapProvider.parseRoute(route().toString(), "Test destination");
        assertEquals(3, plan.points.size()); assertEquals(3, plan.maneuvers.size());
        assertEquals("Turn right onto Test Street", plan.maneuvers.get(1).instruction);
        assertEquals(60, plan.seconds, 0); assertTrue(plan.hasTimingProfile());
        assertEquals(40, plan.remainingSecondsAt(plan.cumulativeMeters[1]), .001);
        assertTrue(Double.isNaN(plan.speedLimitKphAt(1))); assertFalse(plan.offline);
    }
    @Test public void rejectsInvalidManeuverGeometry() throws Exception {
        JSONObject root = route(); root.getJSONObject("trip").getJSONArray("legs").getJSONObject(0)
                .getJSONArray("maneuvers").getJSONObject(1).put("end_shape_index", 500);
        try { OwnMapProvider.parseRoute(root.toString(), "Test"); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void serverErrorsDoNotBecomeDirections() throws Exception {
        for (String value : Arrays.asList("{\"error\":\"private provider detail\"}", "{\"trip\":{\"status\":1}}")) {
            try { OwnMapProvider.parseRoute(value, "Test"); fail(); }
            catch (IllegalArgumentException expected) { assertEquals("No driving route was found", expected.getMessage()); }
        }
    }
    @Test public void searchKeepsAddressAndSkipsInvalidResults() throws Exception {
        JSONArray features = new JSONArray()
                .put(feature("Named café", 47.6, -122.3))
                .put(feature("Invalid", 123, -122.3))
                .put(new JSONObject().put("geometry", new JSONObject().put("type", "Polygon")))
                .put(feature("Other place", 47.7, -122.2));
        List<OwnMapProvider.Place> result = OwnMapProvider.parseSearch(new JSONObject().put("features", features).toString());
        assertEquals(2, result.size());
        assertEquals("Named café, 101 Fixture Lane, Test City, WA", result.get(0).name);
        assertEquals(-122.3, result.get(0).point.longitude, 0);
    }
    @Test public void searchBoundsUntrustedNamesAndResultCount() throws Exception {
        JSONArray features = new JSONArray();
        for (int i = 0; i < 50; i++) features.put(feature("A".repeat(1000), 47.6, -122.3));
        List<OwnMapProvider.Place> result = OwnMapProvider.parseSearch(new JSONObject().put("features", features).toString());
        assertEquals(8, result.size()); assertEquals(240, result.get(0).name.length());
    }
    @Test public void unnamedStreetAddressesAreSearchable() throws Exception {
        List<OwnMapProvider.Place> result = OwnMapProvider.parseSearch(new JSONObject().put("features", new JSONArray()
                .put(feature("", 47.6, -122.3))).toString());
        assertEquals("101 Fixture Lane, Test City, WA", result.get(0).name);
    }
    @Test public void candidateRoutesKeepPrimaryAndAtMostTwoAlternativesInProviderOrder() throws Exception {
        JSONObject primary = namedRoute("Primary");
        primary.put("alternates", new JSONArray().put(namedRoute("Alternative one"))
                .put(namedRoute("Alternative two")).put(namedRoute("Ignored third alternative")));
        List<RoutePlan> candidates = OwnMapProvider.parseRouteCandidates(primary.toString(), "Destination");
        assertEquals(3, candidates.size());
        assertEquals("Primary", candidates.get(0).maneuvers.get(0).instruction);
        assertEquals("Alternative one", candidates.get(1).maneuvers.get(0).instruction);
        assertEquals("Alternative two", candidates.get(2).maneuvers.get(0).instruction);
        assertThrows(UnsupportedOperationException.class, () -> candidates.add(candidates.get(0)));
    }
    @Test public void malformedAlternativeCannotHideValidPrimaryOrOtherBoundedAlternative() throws Exception {
        JSONObject primary = namedRoute("Primary");
        JSONObject malformed = namedRoute("Bad geometry");
        steps(malformed).getJSONObject(1).put("end_shape_index", 999);
        primary.put("alternates", new JSONArray().put(malformed).put(namedRoute("Valid second")));
        List<RoutePlan> candidates = OwnMapProvider.parseRouteCandidates(primary.toString(), "Destination");
        assertEquals(2, candidates.size());
        assertEquals("Primary", candidates.get(0).maneuvers.get(0).instruction);
        assertEquals("Valid second", candidates.get(1).maneuvers.get(0).instruction);
    }
    @Test public void unsupportedAlternativeSchemasAreIgnoredRatherThanCoerced() throws Exception {
        JSONObject primary = namedRoute("Primary");
        primary.put("alternates", new JSONArray().put("not a route object")
                .put(new JSONObject().put("trip", new JSONObject().put("status", 1))));
        assertEquals(1, OwnMapProvider.parseRouteCandidates(primary.toString(), "Destination").size());
        primary.put("alternates", new JSONObject().put("trip", "wrong array schema"));
        assertEquals(1, OwnMapProvider.parseRouteCandidates(primary.toString(), "Destination").size());
    }
    @Test public void invalidPrimaryIsNotSilentlyReplacedWithAnAlternative() throws Exception {
        JSONObject primary = route();
        primary.getJSONObject("trip").put("status", 1);
        primary.put("alternates", new JSONArray().put(namedRoute("Plausible alternative")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnMapProvider.parseRouteCandidates(primary.toString(), "Destination"));
        assertEquals("No driving route was found", error.getMessage());
    }
    @Test public void parsesStreetAndSignLabelsFromOnlyTheirDocumentedSchemas() throws Exception {
        JSONObject response = route();
        steps(response).getJSONObject(1)
                .put("street_names", new JSONArray().put("  Main Street  ").put(42).put("")
                        .put(JSONObject.NULL).put(new JSONObject().put("text", "Not a street string")).put("WA 99"))
                .put("begin_street_names", new JSONArray().put("  Old Road  "))
                .put("sign", new JSONObject()
                        .put("exit_number_elements", new JSONArray().put(new JSONObject().put("text", "  42  "))
                                .put(new JSONObject().put("text", 42)).put(JSONObject.NULL))
                        .put("exit_branch_elements", new JSONArray().put("Not a sign object")
                                .put(new JSONObject().put("text", "WA 99")))
                        .put("exit_toward_elements", new JSONArray().put(new JSONObject().put("text", "Airport")))
                        .put("exit_name_elements", new JSONArray().put(new JSONObject().put("text", "South exit")))
                        .put("unrecognized_elements", new JSONArray().put(new JSONObject().put("text", "Ignored"))));
        RoutePlan.Maneuver item = OwnMapProvider.parseRoute(response.toString(), "Destination").maneuvers.get(1);
        assertEquals(Arrays.asList("Main Street", "WA 99"), item.streetNames);
        assertEquals(Collections.singletonList("Old Road"), item.beginStreetNames);
        assertEquals(Arrays.asList("42", "WA 99", "Airport", "South exit"), item.signTexts);
        assertThrows(UnsupportedOperationException.class, () -> item.streetNames.add("Changed"));
        assertThrows(UnsupportedOperationException.class, () -> item.beginStreetNames.clear());
        assertThrows(UnsupportedOperationException.class, () -> item.signTexts.remove(0));
    }
    @Test public void streetArraysAndEveryLabelAreBoundedBeforeRouteConstruction() throws Exception {
        JSONObject response = route();
        JSONArray streetNames = new JSONArray();
        JSONArray beginStreetNames = new JSONArray();
        for (int index = 0; index < 50; index++) {
            streetNames.put(index == 0 ? " " + "A".repeat(1000) + " " : "Road " + index);
            beginStreetNames.put("Begin " + index);
        }
        steps(response).getJSONObject(1).put("street_names", streetNames).put("begin_street_names", beginStreetNames);
        RoutePlan.Maneuver item = OwnMapProvider.parseRoute(response.toString(), "Destination").maneuvers.get(1);
        assertEquals(32, item.streetNames.size()); assertEquals(240, item.streetNames.get(0).length());
        assertEquals("Road 31", item.streetNames.get(31));
        assertEquals(32, item.beginStreetNames.size()); assertEquals("Begin 31", item.beginStreetNames.get(31));
    }
    @Test public void signArraysAreIndividuallyCappedAndCombinedAtSixtyFourLabels() throws Exception {
        JSONObject response = route();
        JSONObject sign = new JSONObject();
        for (String field : Arrays.asList("exit_number_elements", "exit_branch_elements", "exit_toward_elements", "exit_name_elements")) {
            JSONArray values = new JSONArray();
            for (int index = 0; index < 24; index++) values.put(new JSONObject().put("text", " " + "X".repeat(1000) + " "));
            sign.put(field, values);
        }
        steps(response).getJSONObject(1).put("sign", sign);
        RoutePlan.Maneuver item = OwnMapProvider.parseRoute(response.toString(), "Destination").maneuvers.get(1);
        assertEquals(64, item.signTexts.size());
        for (String value : item.signTexts) assertEquals(240, value.length());
    }
    @Test public void absentOrWrongTypedRoadLabelFieldsStayEmpty() throws Exception {
        JSONObject response = route();
        steps(response).getJSONObject(1).put("street_names", "Main Street")
                .put("begin_street_names", new JSONObject().put("name", "Old Road"))
                .put("sign", new JSONArray().put("Airport"));
        RoutePlan plan = OwnMapProvider.parseRoute(response.toString(), "Destination");
        for (RoutePlan.Maneuver item : plan.maneuvers) {
            assertTrue(item.streetNames.isEmpty()); assertTrue(item.beginStreetNames.isEmpty()); assertTrue(item.signTexts.isEmpty());
        }
    }
    @Test public void maneuverLabelsDefensivelyCopyCallerLists() {
        List<String> labels = new ArrayList<>(Arrays.asList(" Main Street ", "  "));
        RoutePlan.Maneuver item = new RoutePlan.Maneuver(0, 1, "Turn right", "Turn right", 10, 1,
                labels, labels, labels);
        labels.clear(); labels.add("Mutated later");
        assertEquals(Collections.singletonList("Main Street"), item.streetNames);
        assertEquals(Collections.singletonList("Main Street"), item.beginStreetNames);
        assertEquals(Collections.singletonList("Main Street"), item.signTexts);
    }
    @Test public void directManeuverConstructionRejectsUnboundedOrNullLabelData() {
        for (List<String> invalid : Arrays.<List<String>>asList(Collections.nCopies(65, "Road"),
                Collections.singletonList("X".repeat(241)), Collections.singletonList(null))) {
            assertThrows(IllegalArgumentException.class, () -> new RoutePlan.Maneuver(0, 1, "Turn", "Turn", 10, 1,
                    invalid, Collections.emptyList(), Collections.emptyList()));
        }
    }
    private JSONArray steps(JSONObject value) throws Exception {
        return value.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).getJSONArray("maneuvers");
    }
    private JSONObject namedRoute(String firstInstruction) throws Exception {
        JSONObject value = route(); steps(value).getJSONObject(0).put("instruction", firstInstruction); return value;
    }
    private JSONObject feature(String name, double lat, double lon) throws Exception {
        return new JSONObject().put("geometry", new JSONObject().put("type", "Point")
                .put("coordinates", new JSONArray().put(lon).put(lat)))
                .put("properties", new JSONObject().put("name", name).put("housenumber", "101")
                        .put("street", "Fixture Lane").put("city", "Test City").put("state", "WA"));
    }
}
