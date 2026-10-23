package org.openandroidauto.navigation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public final class NavigationProvidersTest {
    private String fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/navigation/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void parsesActualPublicLandmarkRouteAndReplaysFullGeometry() throws Exception {
        RoutePlan route = NavigationProviders.parseRoute(fixture("valhalla-seattle.json"), "Seattle Center");
        assertTrue(route.points.size() > 20);
        assertTrue(route.lengthMeters() > 1000);
        assertTrue(route.lengthMeters() < 10000);
        assertTrue(route.maneuvers.size() >= 3);
        RouteTracker tracker = new RouteTracker(route);
        double previous = 0;
        for (int index = 0; index < route.points.size(); index++) {
            RouteTracker.Progress progress = tracker.update(route.points.get(index), 5, (index + 1) * 1000L);
            assertFalse("Replay should remain on route", progress.offRoute);
            assertTrue("Replay must not jump backward", progress.alongMeters + 10 >= previous);
            previous = progress.alongMeters;
        }
        assertEquals(route.lengthMeters(), previous, 30);
    }

    @Test public void parsesActualPhotonPlaceNamesAndCoordinates() throws Exception {
        List<NavigationProviders.Place> places = NavigationProviders.parseSearch(fixture("photon-seattle-library.json"));
        assertFalse(places.isEmpty());
        assertTrue(places.get(0).name.contains("Seattle"));
        assertTrue(places.get(0).point.latitude > 47 && places.get(0).point.latitude < 48);
        assertTrue(places.get(0).point.longitude < -122 && places.get(0).point.longitude > -123);
    }

    @Test public void mergesSharedLegBoundaryAndOffsetsManeuvers() throws Exception {
        JSONObject response = new JSONObject(fixture("valhalla-seattle.json"));
        JSONObject first = response.getJSONObject("trip").getJSONArray("legs").getJSONObject(0);
        JSONObject second = new JSONObject(first.toString());
        first.put("shape", RouteTrackerTest.encode(0, 0, 0, .001));
        first.put("maneuvers", new JSONArray().put(step(0, 1, "First")));
        second.put("shape", RouteTrackerTest.encode(0, .001, 0, .002));
        second.put("maneuvers", new JSONArray().put(step(0, 1, "Second")));
        response.getJSONObject("trip").put("legs", new JSONArray().put(first).put(second));
        RoutePlan route = NavigationProviders.parseRoute(response.toString(), "Multi leg");
        assertEquals(3, route.points.size());
        assertEquals(1, route.maneuvers.get(1).beginIndex);
        assertEquals(2, route.maneuvers.get(1).endIndex);
    }

    @Test public void refusesServerErrorsAndOutOfBoundsManeuvers() throws Exception {
        try { NavigationProviders.parseRoute("{\"error\":\"No route\"}", "Bad"); fail(); }
        catch (IllegalArgumentException expected) {}
        JSONObject response = new JSONObject(fixture("valhalla-seattle.json"));
        response.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).getJSONArray("maneuvers")
                .getJSONObject(0).put("end_shape_index", 999999);
        try { NavigationProviders.parseRoute(response.toString(), "Bad"); fail(); }
        catch (IllegalArgumentException expected) {}
    }

    private JSONObject step(int begin, int end, String instruction) throws Exception {
        return new JSONObject().put("begin_shape_index", begin).put("end_shape_index", end)
                .put("instruction", instruction).put("type", 1);
    }
}
