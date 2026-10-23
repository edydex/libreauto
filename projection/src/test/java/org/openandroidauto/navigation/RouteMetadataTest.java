// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RouteMetadataTest {
    private String fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/navigation/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private RoutePlan route() {
        return new RoutePlan(Arrays.asList(new GeoPoint(0, 0), new GeoPoint(0, .001),
                new GeoPoint(0, .002), new GeoPoint(0, .003)), Arrays.asList(
                new RoutePlan.Maneuver(0, 1, "Head east", "Head east", 1, 10),
                new RoutePlan.Maneuver(1, 3, "Continue", "Continue", 8, 90),
                new RoutePlan.Maneuver(3, 3, "Arrive", "Arrive", 4, 0)), 100, "Public test");
    }
    private JSONObject edge(int begin, int end, Object limit, Object time) throws Exception {
        JSONObject edge = new JSONObject().put("begin_shape_index", begin).put("end_shape_index", end);
        if (limit != null) edge.put("speed_limit", limit);
        if (time != null) edge.put("end_node", new JSONObject().put("elapsed_time", time));
        return edge;
    }
    private JSONObject metadata(RoutePlan route) throws Exception {
        return new JSONObject().put("shape", RoutePlan.encodePolyline6(route.points)).put("units", "kilometers")
                .put("edges", new JSONArray().put(edge(0, 1, null, 10)).put(edge(1, 3, 40, 100)))
                .put("shape_attributes", new JSONObject().put("time", new JSONArray(List.of(10, 20, 20)))
                        .put("speed_limit", new JSONArray(List.of(0, 40, 40))));
    }

    @Test public void livePublicResponseMatchesGeometryAndKeepsOriginalTotalDuration() throws Exception {
        RoutePlan basic = NavigationProviders.parseRoute(fixture("valhalla-seattle.json"), "Seattle Center");
        RoutePlan enriched = NavigationProviders.parseRouteMetadata(fixture("valhalla-seattle-attributes.json"), basic);
        assertTrue(basic.hasTimingProfile()); assertTrue(enriched.metadataEnriched);
        assertEquals(300.443, enriched.remainingSecondsAt(0), .001);
        assertEquals(0, enriched.remainingSecondsAt(enriched.lengthMeters()), .001);
        assertTrue(Double.isNaN(enriched.speedLimitKphAt(0)));
        assertEquals(40, enriched.speedLimitKphAt(enriched.cumulativeMeters[2] + 1), .001);
        double previous = enriched.seconds;
        for (double along : enriched.cumulativeMeters) {
            double remaining = enriched.remainingSecondsAt(along);
            assertTrue(remaining >= 0 && remaining <= previous + .001); previous = remaining;
        }
    }
    @Test public void immediateEtaUsesServerManeuverTimesInsteadOfOneAverageSpeed() {
        RoutePlan route = route();
        assertEquals(95, route.remainingSecondsAt(route.cumulativeMeters[1] / 2), .001);
        assertEquals(90, route.remainingSecondsAt(route.cumulativeMeters[1]), .001);
        assertEquals(45, route.remainingSecondsAt(route.cumulativeMeters[2]), .001);
    }
    @Test public void publicChurchRouteAcceptsOnlyItsTwoMeterEndpointTrim() throws Exception {
        RoutePlan base = NavigationProviders.parseRoute(fixture("valhalla-public-church.json"), "Word of Truth");
        String metadata = fixture("valhalla-public-church-attributes.json");
        assertEquals(421, base.points.size());
        assertEquals(420, RoutePlan.decodePolyline6(new JSONObject(metadata).getString("shape")).size());
        RoutePlan enriched = NavigationProviders.parseRouteMetadata(metadata, base);
        assertTrue(enriched.metadataEnriched); assertEquals(base.points, enriched.points);
        assertEquals(1280.990, enriched.remainingSecondsAt(0), .001);
        assertTrue(Double.isNaN(enriched.speedLimitKphAt(0)));
        assertEquals(40, enriched.speedLimitKphAt(base.cumulativeMeters[2] + 1), .001);
        assertEquals(97, enriched.speedLimitKphAt((base.cumulativeMeters[215] + base.cumulativeMeters[216]) / 2), .001);
    }
    private RoutePlan tinyEndpoints() {
        return new RoutePlan(List.of(new GeoPoint(0, 0), new GeoPoint(0, .00002),
                new GeoPoint(0, .001), new GeoPoint(0, .002), new GeoPoint(0, .00202)),
                List.of(new RoutePlan.Maneuver(0, 4, "Continue", "Continue", 8, 100)), 100, "Endpoint test");
    }
    private JSONObject trimmedMetadata(RoutePlan base, int begin, int end) throws Exception {
        double duration = base.remainingSecondsAt(base.cumulativeMeters[begin])
                - base.remainingSecondsAt(base.cumulativeMeters[end]);
        JSONArray limits = new JSONArray(), times = new JSONArray();
        for (int index = begin; index < end; index++) {
            limits.put(40);
            times.put(duration * (base.cumulativeMeters[index + 1] - base.cumulativeMeters[index])
                    / (base.cumulativeMeters[end] - base.cumulativeMeters[begin]));
        }
        return new JSONObject().put("shape", RoutePlan.encodePolyline6(base.points.subList(begin, end + 1)))
                .put("units", "kilometers").put("edges", new JSONArray().put(edge(0, end - begin, 40, duration)))
                .put("shape_attributes", new JSONObject().put("time", times).put("speed_limit", limits));
    }
    @Test public void exactTinyEndpointTrimsKeepOriginalGeometryAndUnknownTrimmedLimits() throws Exception {
        RoutePlan base = tinyEndpoints();
        for (int[] bounds : new int[][] {{1, 4}, {0, 3}, {1, 3}}) {
            RoutePlan enriched = NavigationProviders.parseRouteMetadata(trimmedMetadata(base, bounds[0], bounds[1]).toString(), base);
            assertTrue(enriched.metadataEnriched);
            assertEquals(base.points, enriched.points); assertEquals(base.maneuvers, enriched.maneuvers);
            assertEquals(100, enriched.remainingSecondsAt(0), .001);
            assertEquals(0, enriched.remainingSecondsAt(enriched.lengthMeters()), .001);
            for (int index = 0; index + 1 < base.points.size(); index++) {
                double along = (base.cumulativeMeters[index] + base.cumulativeMeters[index + 1]) / 2;
                if (index < bounds[0] || index >= bounds[1]) {
                    assertTrue(Double.isNaN(enriched.speedLimitKphAt(along)));
                    assertEquals(base.remainingSecondsAt(base.cumulativeMeters[index])
                                    - base.remainingSecondsAt(base.cumulativeMeters[index + 1]),
                            enriched.remainingSecondsAt(base.cumulativeMeters[index])
                                    - enriched.remainingSecondsAt(base.cumulativeMeters[index + 1]), .001);
                } else assertEquals(40, enriched.speedLimitKphAt(along), .001);
            }
        }
    }
    @Test public void trimmedShapeStillRejectsAnInternalChangeOrSkippedVertex() throws Exception {
        RoutePlan base = tinyEndpoints();
        JSONObject changed = trimmedMetadata(base, 1, 4);
        changed.put("shape", RoutePlan.encodePolyline6(List.of(base.points.get(1), new GeoPoint(.000001, .001),
                base.points.get(3), base.points.get(4))));
        rejected(changed, base);
        changed.put("shape", RoutePlan.encodePolyline6(List.of(base.points.get(1), base.points.get(3), base.points.get(4))));
        rejected(changed, base);
    }
    @Test public void endpointTrimIsBoundedByPathLengthAtBothEnds() throws Exception {
        RoutePlan base = route();
        rejected(trimmedMetadata(base, 1, 3), base);
        rejected(trimmedMetadata(base, 0, 2), base);
        // Endpoints can be physically close after a loop; the omitted traveled path still exceeds 5m.
        RoutePlan loop = new RoutePlan(List.of(new GeoPoint(0, 0), new GeoPoint(0, .001),
                new GeoPoint(0, .00001), new GeoPoint(0, .002)),
                List.of(new RoutePlan.Maneuver(0, 3, "Continue", "Continue", 8, 100)), 100, "Loop");
        rejected(trimmedMetadata(loop, 2, 3), loop);
    }
    @Test public void repeatedExactSubsequenceIsRejectedEvenWithTinyEndpointPaths() throws Exception {
        GeoPoint a = new GeoPoint(0, 0), b = new GeoPoint(0, .000001);
        RoutePlan repeated = new RoutePlan(List.of(a, b, a, b),
                List.of(new RoutePlan.Maneuver(0, 3, "Continue", "Continue", 8, 100)), 100, "Repeated");
        rejected(trimmedMetadata(repeated, 0, 1), repeated);
    }
    @Test public void submeterEndpointReprojectionKeepsGeometryAndClearsOnlyAffectedLimits() throws Exception {
        RoutePlan base = route();
        for (int mask = 1; mask <= 3; mask++) {
            JSONObject data = metadata(base);
            data.getJSONObject("shape_attributes").put("speed_limit", new JSONArray(List.of(40, 50, 60)));
            List<GeoPoint> points = new ArrayList<>(base.points);
            if ((mask & 1) != 0) points.set(0, new GeoPoint(.000001, 0));
            if ((mask & 2) != 0) points.set(3, new GeoPoint(-.000001, .003));
            data.put("shape", RoutePlan.encodePolyline6(points));
            RoutePlan enriched = NavigationProviders.parseRouteMetadata(data.toString(), base);
            assertTrue(enriched.metadataEnriched); assertEquals(base.points, enriched.points);
            assertEquals(base.maneuvers, enriched.maneuvers);
            assertEquals(100, enriched.remainingSecondsAt(0), .001);
            if ((mask & 1) != 0) assertTrue(Double.isNaN(enriched.speedLimitKphAt(0)));
            else assertEquals(40, enriched.speedLimitKphAt(0), .001);
            assertEquals(50, enriched.speedLimitKphAt(base.cumulativeMeters[1] + 1), .001);
            if ((mask & 2) != 0) assertTrue(Double.isNaN(enriched.speedLimitKphAt(base.cumulativeMeters[2] + 1)));
            else assertEquals(60, enriched.speedLimitKphAt(base.cumulativeMeters[2] + 1), .001);
        }
    }
    @Test public void evenSeventyFiveMillimeterInteriorChangeIsRejected() throws Exception {
        RoutePlan timed = route();
        List<GeoPoint> points = new ArrayList<>();
        for (GeoPoint point : timed.points) points.add(new GeoPoint(47.5, point.longitude));
        RoutePlan base = new RoutePlan(points, timed.maneuvers, timed.seconds, "Interior test");
        List<GeoPoint> changed = new ArrayList<>(base.points);
        changed.set(1, new GeoPoint(47.5, .001001));
        assertEquals(.075, changed.get(1).distanceTo(base.points.get(1)), .001);
        rejected(metadata(base).put("shape", RoutePlan.encodePolyline6(changed)), base);
    }
    @Test public void endpointReprojectionOverOneMeterOrMixedWithATrimIsRejected() throws Exception {
        RoutePlan base = route();
        for (int endpoint : new int[] {0, 3}) {
            List<GeoPoint> points = new ArrayList<>(base.points);
            points.set(endpoint, new GeoPoint(.00001, points.get(endpoint).longitude));
            rejected(metadata(base).put("shape", RoutePlan.encodePolyline6(points)), base);
        }
        RoutePlan tiny = tinyEndpoints();
        List<GeoPoint> mixed = new ArrayList<>(tiny.points.subList(1, 5));
        mixed.set(0, new GeoPoint(.000001, mixed.get(0).longitude));
        rejected(trimmedMetadata(tiny, 1, 4).put("shape", RoutePlan.encodePolyline6(mixed)), tiny);
    }
    @Test public void endpointReprojectionRequiresAnExactUniqueInteriorAnchor() throws Exception {
        GeoPoint origin = new GeoPoint(0, 0);
        RoutePlan repeated = new RoutePlan(List.of(origin, origin, origin, origin),
                List.of(new RoutePlan.Maneuver(0, 3, "Continue", "Continue", 8, 100)), 100, "Repeated");
        rejected(metadata(repeated).put("shape", RoutePlan.encodePolyline6(List.of(new GeoPoint(.000001, 0),
                origin, origin, new GeoPoint(-.000001, 0)))), repeated);
        // A two-point line has no interior evidence, even when both endpoints are close.
        RoutePlan shortRoute = new RoutePlan(List.of(new GeoPoint(0, 0), new GeoPoint(0, .001)),
                List.of(new RoutePlan.Maneuver(0, 1, "Continue", "Continue", 8, 100)), 100, "Short");
        rejected(new JSONObject().put("shape", RoutePlan.encodePolyline6(List.of(new GeoPoint(.000001, 0),
                new GeoPoint(0, .001)))), shortRoute);
        // A unique one-vertex interior anchor on a three-point route remains acceptable.
        RoutePlan three = new RoutePlan(List.of(new GeoPoint(0, 0), new GeoPoint(0, .001), new GeoPoint(0, .002)),
                List.of(new RoutePlan.Maneuver(0, 2, "Continue", "Continue", 8, 100)), 100, "Three");
        JSONObject data = new JSONObject().put("shape", RoutePlan.encodePolyline6(List.of(new GeoPoint(.000001, 0),
                three.points.get(1), three.points.get(2)))).put("units", "kilometers")
                .put("edges", new JSONArray().put(edge(0, 2, 40, 100)));
        RoutePlan enriched = NavigationProviders.parseRouteMetadata(data.toString(), three);
        assertTrue(Double.isNaN(enriched.speedLimitKphAt(0)));
        assertEquals(40, enriched.speedLimitKphAt(three.cumulativeMeters[1] + 1), .001);
    }
    @Test public void trimmedLegacyRouteKeepsUnknownEtaAndValidAlignedLimits() throws Exception {
        RoutePlan timed = tinyEndpoints();
        RoutePlan legacy = new RoutePlan(timed.points, List.of(new RoutePlan.Maneuver(0, 4, "Continue", "Continue", 8)), 100, "Legacy");
        RoutePlan enriched = NavigationProviders.parseRouteMetadata(trimmedMetadata(timed, 1, 3).toString(), legacy);
        assertTrue(Double.isNaN(enriched.remainingSecondsAt(1)));
        assertTrue(Double.isNaN(enriched.speedLimitKphAt(1)));
        assertEquals(40, enriched.speedLimitKphAt(legacy.cumulativeMeters[1] + 1), .001);
    }
    @Test public void transitionDelaysAreNotLostFromRawShapeTimes() throws Exception {
        RoutePlan base = route();
        RoutePlan enriched = NavigationProviders.parseRouteMetadata(metadata(base).toString(), base);
        assertEquals(90, enriched.remainingSecondsAt(base.cumulativeMeters[1]), .001);
        // Raw shape traversal times total50s, but Valhalla node elapsed includes50s of delays.
        assertEquals(20, enriched.remainingSecondsAt(base.cumulativeMeters[2]), .001);
        assertEquals(55, enriched.remainingSecondsAt((base.cumulativeMeters[1] + base.cumulativeMeters[2]) / 2), .001);
    }
    @Test public void unknownAndUnlimitedAreDifferentAndNeverBecomeAZeroMphLimit() throws Exception {
        RoutePlan base = route(); JSONObject data = metadata(base);
        data.getJSONObject("shape_attributes").put("speed_limit", new JSONArray(List.of(0, 255, 40)));
        RoutePlan enriched = NavigationProviders.parseRouteMetadata(data.toString(), base);
        assertTrue(Double.isNaN(enriched.speedLimitKphAt(0)));
        assertEquals(Double.POSITIVE_INFINITY, enriched.speedLimitKphAt(base.cumulativeMeters[1]), 0);
        assertEquals(40, enriched.speedLimitKphAt(base.cumulativeMeters[2]), .001);
    }
    @Test public void shapeLimitsStayKphEvenWhenResponseUnitsSayMiles() throws Exception {
        RoutePlan base = route(); JSONObject data = metadata(base).put("units", "miles");
        RoutePlan enriched = NavigationProviders.parseRouteMetadata(data.toString(), base);
        assertEquals(40, enriched.speedLimitKphAt(base.cumulativeMeters[1]), .001);
    }
    @Test public void edgeFallbackConvertsMphAndUnderstandsExplicitUnlimited() throws Exception {
        RoutePlan base = route(); JSONObject data = metadata(base).put("units", "miles");
        data.getJSONObject("shape_attributes").remove("speed_limit");
        data.put("edges", new JSONArray().put(edge(0, 1, 25, 10)).put(edge(1, 3, "unlimited", 100)));
        RoutePlan enriched = NavigationProviders.parseRouteMetadata(data.toString(), base);
        assertEquals(40.2336, enriched.speedLimitKphAt(0), .0001);
        assertEquals(Double.POSITIVE_INFINITY, enriched.speedLimitKphAt(base.cumulativeMeters[1]), 0);
    }
    @Test public void malformedLimitValuesAreUnknownRatherThanCoerced() throws Exception {
        RoutePlan base = route(); JSONObject data = metadata(base);
        data.getJSONObject("shape_attributes").put("speed_limit", new JSONArray().put(-1).put("40").put(JSONObject.NULL));
        RoutePlan enriched = NavigationProviders.parseRouteMetadata(data.toString(), base);
        for (double along : new double[] {0, base.cumulativeMeters[1], base.cumulativeMeters[2]})
            assertTrue(Double.isNaN(enriched.speedLimitKphAt(along)));
    }
    @Test public void aDifferentGeometryOrArrayLengthCannotAttachLimitsToTheWrongRoad() throws Exception {
        RoutePlan base = route(); JSONObject data = metadata(base).put("shape", RouteTrackerTest.encode(0, 0, 0, .004));
        rejected(data, base);
        data = metadata(base); data.getJSONObject("shape_attributes").put("speed_limit", new JSONArray(List.of(40)));
        rejected(data, base);
        data = metadata(base); data.getJSONObject("shape_attributes").put("time", new JSONArray(List.of(10)));
        rejected(data, base);
    }
    @Test public void discontinuousFractionalAndCoercedIndexesAreRejected() throws Exception {
        RoutePlan base = route();
        for (Object begin : new Object[] {2, -1, .5, "1"}) {
            JSONObject data = metadata(base); data.getJSONArray("edges").getJSONObject(1).put("begin_shape_index", begin);
            rejected(data, base);
        }
    }
    @Test public void unavailableOrInconsistentNodeTimingKeepsManeuverEtaAndValidLimits() throws Exception {
        RoutePlan base = route();
        for (Object elapsed : new Object[] {JSONObject.NULL, 5, 9999, "100"}) {
            JSONObject data = metadata(base); data.getJSONArray("edges").getJSONObject(1).getJSONObject("end_node").put("elapsed_time", elapsed);
            RoutePlan enriched = NavigationProviders.parseRouteMetadata(data.toString(), base);
            assertEquals(45, enriched.remainingSecondsAt(base.cumulativeMeters[2]), .001);
            assertEquals(40, enriched.speedLimitKphAt(base.cumulativeMeters[1]), .001);
        }
    }
    @Test public void noMetadataDataLeavesBasicRouteByIdentity() throws Exception {
        RoutePlan base = route(); JSONObject data = metadata(base); data.remove("shape_attributes");
        data.put("edges", new JSONArray().put(edge(0, 3, null, null)));
        assertSame(base, NavigationProviders.parseRouteMetadata(data.toString(), base));
    }
    @Test public void legacyConstructorsDoNotInventANewTravelSpeed() {
        RoutePlan base = new RoutePlan(route().points, List.of(new RoutePlan.Maneuver(0, 3, "Continue", "Continue", 8)), 100, "Legacy");
        assertFalse(base.hasTimingProfile()); assertTrue(Double.isNaN(base.remainingSecondsAt(50)));
    }
    @Test public void metadataArraysAreCopiedAndBoundsAreValidated() {
        RoutePlan base = route(); double[] times = {10, 70, 20}, limits = {40, 50, 60};
        RoutePlan enriched = base.withRoadMetadata(times, limits); times[0] = 999; limits[0] = 150;
        assertEquals(40, enriched.speedLimitKphAt(0), .001); assertEquals(90, enriched.remainingSecondsAt(base.cumulativeMeters[1]), .001);
        try { base.withRoadMetadata(new double[] {1}, limits); fail(); } catch (IllegalArgumentException expected) {}
        try { base.withRoadMetadata(times, new double[] {0, 50, 60}); fail(); } catch (IllegalArgumentException expected) {}
    }
    @Test public void metadataEndpointStaysOnConfiguredOriginAndPreservesEncodedQuery() {
        assertEquals("https://example.org/custom/trace_attributes?key=a%2Fb", NavigationProviders.metadataUrl("https://example.org/custom/route?key=a%2Fb"));
        for (String url : List.of("http://example.org/route", "https://user@example.org/route", "https://example.org/other", "https://example.org/route#part"))
            assertNull(NavigationProviders.metadataUrl(url));
    }
    @Test public void cancellationNeverTurnsIntoASuccessfulMetadataFallback() throws Exception {
        NavigationProviders providers = new NavigationProviders(new NavigationConfig(NavigationConfig.DEFAULT_STYLE,
                "https://example.org/unsupported", NavigationConfig.DEFAULT_SEARCH, false, 30));
        RoutePlan base = route();
        assertSame(base, providers.enrichRoute(base));
        Thread.currentThread().interrupt();
        try { providers.enrichRoute(route()); fail(); } catch (InterruptedException expected) {}
        finally { Thread.interrupted(); }
        assertTrue(providers.diagnosticFlags().startsWith("metadata=CANCELLED,"));
    }
    @Test public void metadataDiagnosticsContainOnlyStableCategoriesTimesAndPointCounts() throws Exception {
        NavigationProviders providers = new NavigationProviders(new NavigationConfig(NavigationConfig.DEFAULT_STYLE,
                "https://example.org/unsupported?private=do-not-retain", NavigationConfig.DEFAULT_SEARCH, false, 30));
        assertEquals("metadata=NOT_REQUESTED,metadataMs=0,metadataRoutePoints=0,metadataShapePoints=-1,metadataValidation=NOT_CHECKED,metadataGeometryChangedPoints=-1,metadataGeometryChangedInteriorPoints=-1,metadataGeometryMaxDeltaMm=-1", providers.diagnosticFlags());
        providers.enrichRoute(route());
        assertTrue(providers.diagnosticFlags().matches("metadata=SKIPPED_ENDPOINT,metadataMs=[0-9]+,metadataRoutePoints=4,metadataShapePoints=-1,metadataValidation=NOT_CHECKED,metadataGeometryChangedPoints=-1,metadataGeometryChangedInteriorPoints=-1,metadataGeometryMaxDeltaMm=-1"));
        assertFalse(providers.diagnosticFlags().contains("example"));
        assertFalse(providers.diagnosticFlags().contains("private"));
    }
    @Test public void enrichmentSkipDiagnosticsDistinguishAlreadyLoadedAndOversizedGeometry() throws Exception {
        NavigationProviders providers = new NavigationProviders(new NavigationConfig(NavigationConfig.DEFAULT_STYLE,
                NavigationConfig.DEFAULT_ROUTE, NavigationConfig.DEFAULT_SEARCH, false, 30));
        RoutePlan loaded = route().withRoadMetadata(new double[] {10, 20, 70}, new double[] {40, 40, 40});
        assertSame(loaded, providers.enrichRoute(loaded));
        assertTrue(providers.diagnosticFlags().startsWith("metadata=SKIPPED_ALREADY_ENRICHED,"));
        List<GeoPoint> points = new ArrayList<>();
        for (int index = 0; index < 10001; index++) points.add(new GeoPoint(0, index / 1000000.0));
        RoutePlan large = new RoutePlan(points, List.of(new RoutePlan.Maneuver(0, 10000, "Continue", "Continue", 8, 100)), 100, "Large");
        assertSame(large, providers.enrichRoute(large));
        assertTrue(providers.diagnosticFlags().matches("metadata=SKIPPED_POINT_LIMIT,metadataMs=[0-9]+,metadataRoutePoints=10001,metadataShapePoints=-1,metadataValidation=NOT_CHECKED,metadataGeometryChangedPoints=-1,metadataGeometryChangedInteriorPoints=-1,metadataGeometryMaxDeltaMm=-1"));
    }
    @Test public void validationDiagnosticsDiscardArbitraryExceptionContent() {
        assertEquals("GEOMETRY", NavigationProviders.validationCategory(new IllegalArgumentException("Optional route metadata geometry differs")));
        assertEquals("INDEX", NavigationProviders.validationCategory(new IllegalArgumentException("Invalid route metadata index")));
        assertEquals("ROUTE_MODEL", NavigationProviders.validationCategory(new IllegalArgumentException("Invalid segment travel time")));
        assertEquals("OTHER", NavigationProviders.validationCategory(new IllegalArgumentException("private route https://example.org/?coordinates=secret")));
        assertEquals("OTHER", NavigationProviders.validationCategory(new IllegalArgumentException()));
    }
    private void rejected(JSONObject value, RoutePlan route) throws Exception {
        try { NavigationProviders.parseRouteMetadata(value.toString(), route); fail("Attached invalid metadata"); }
        catch (IllegalArgumentException expected) {}
    }
}
