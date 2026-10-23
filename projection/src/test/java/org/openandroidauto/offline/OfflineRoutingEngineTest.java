// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.openandroidauto.navigation.GeoPoint;
import org.openandroidauto.navigation.RoutePlan;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class OfflineRoutingEngineTest {
    private JSONObject output(int command) throws Exception {
        return new JSONObject().put("features", new JSONArray().put(new JSONObject()
                .put("geometry", new JSONObject().put("type", "LineString").put("coordinates", new JSONArray()
                        .put(new JSONArray(List.of(0, 0))).put(new JSONArray(List.of(.001, 0))).put(new JSONArray(List.of(.002, 0)))))
                .put("properties", new JSONObject().put("total-time", "12").put("times", new JSONArray(List.of(0, 2, 12)))
                        .put("voicehints", new JSONArray().put(new JSONArray(List.of(1, command, 2)))))));
    }
    private JSONObject properties(JSONObject output) throws Exception { return output.getJSONArray("features").getJSONObject(0).getJSONObject("properties"); }
    private void rejected(JSONObject output) throws Exception {
        try { OfflineRoutingEngine.parseGeoJson(output.toString(), "Test"); fail("Accepted invalid local route"); }
        catch (IOException expected) {}
    }
    @Test public void realOfflineCarRoutePreservesNodeTimingAndKeepsPostedLimitsUnknown() throws Exception {
        String json;
        try (InputStream input = getClass().getResourceAsStream("/navigation/brouter-public-church.json")) {
            assertNotNull(input); json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        RoutePlan route = OfflineRoutingEngine.parseGeoJson(json, "Public destination");
        assertTrue(route.offline); assertEquals(345, route.points.size()); assertTrue(route.maneuvers.size() > 15);
        assertEquals(1276.37, route.remainingSecondsAt(0), .001);
        assertEquals(1276.37 - 3.223, route.remainingSecondsAt(route.cumulativeMeters[1]), .001);
        assertEquals(0, route.remainingSecondsAt(route.lengthMeters()), .001);
        for (double along : route.cumulativeMeters) assertTrue(Double.isNaN(route.speedLimitKphAt(along)));
        assertEquals(4, route.maneuvers.get(route.maneuvers.size() - 1).type);
    }
    @Test public void drivingHintsMapToTheExistingManeuverArrows() throws Exception {
        int[][] cases = {{1,8},{2,15},{3,16},{4,14},{5,10},{6,9},{7,11},{8,24},{9,23},{10,13},{11,12},{13,26},{14,26},{15,13},{17,19},{18,20}};
        for (int[] item : cases) {
            RoutePlan route = OfflineRoutingEngine.parseGeoJson(output(item[0]).toString(), "Test");
            assertEquals(item[1], route.maneuvers.get(1).type);
            assertEquals(10, route.maneuvers.get(1).seconds, .001);
        }
        assertTrue(OfflineRoutingEngine.parseGeoJson(output(13).toString(), "Test").maneuvers.get(1).instruction.contains("exit 2"));
    }
    @Test public void BeelinesOffroadAndUnknownCommandsNeverBecomeDrivingRoutes() throws Exception {
        for (int command : new int[] {12,16,99,100}) rejected(output(command));
    }
    @Test public void timingMustMatchEveryPointAndRemainMonotonic() throws Exception {
        for (JSONArray times : List.of(new JSONArray(List.of(0, 2)), new JSONArray(List.of(0, 3, 2)),
                new JSONArray(List.of(0, "2", 12)), new JSONArray(List.of(0, -1, 12)))) {
            JSONObject data = output(2); properties(data).put("times", times); rejected(data);
        }
        JSONObject missing = output(2); properties(missing).remove("times"); rejected(missing);
    }
    @Test public void duplicateFractionalAndOutOfRangeTurnIndexesAreRejected() throws Exception {
        for (Object point : List.of(-1, .5, 3, "1")) {
            JSONObject data = output(2); properties(data).getJSONArray("voicehints").getJSONArray(0).put(0, point); rejected(data);
        }
        JSONObject duplicate = output(2); properties(duplicate).getJSONArray("voicehints").put(new JSONArray(List.of(1, 5, 0))); rejected(duplicate);
    }
    @Test public void tileNamesUseWesternSouthernFloorsAndEveryBoundaryCell() {
        assertEquals("W125_N45.rd5", OfflineRoutingEngine.tileName(47.6062, -122.3321));
        assertEquals("W5_S5.rd5", OfflineRoutingEngine.tileName(-.001, -.001));
        assertEquals("E0_N0.rd5", OfflineRoutingEngine.tileName(0, 0));
        assertEquals(List.of("W125_N45.rd5"), OfflineRoutingEngine.requiredTileNames(OfflineAreaPolicy.square(47.5, -122.5, 1)));
        assertEquals(List.of("W125_N40.rd5", "W125_N45.rd5", "W130_N40.rd5", "W130_N45.rd5"),
                OfflineRoutingEngine.requiredTileNames(OfflineAreaPolicy.validate(45.01, -124.99, 44.99, -125.01)));
    }
    @Test public void graphHeaderValidationRejectsMissingTruncatedAndBadVersionFiles() throws Exception {
        File temporary = File.createTempFile("offline-graph-invalid", ".rd5");
        try {
            assertFalse(OfflineRoutingEngine.validateGraphFile(new File(temporary.getParentFile(), "missing-file.rd5")));
            assertFalse(OfflineRoutingEngine.validateGraphFile(temporary));
            Files.write(temporary.toPath(), new byte[500]); assertFalse(OfflineRoutingEngine.validateGraphFile(temporary));
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
