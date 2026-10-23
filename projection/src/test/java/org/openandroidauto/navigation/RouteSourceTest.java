// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteSourceTest {
    private RoutePlan route(boolean timed) {
        return new RoutePlan(List.of(new GeoPoint(0, 0), new GeoPoint(0, .001), new GeoPoint(0, .002)),
                List.of(timed ? new RoutePlan.Maneuver(0, 2, "Continue", "Continue", 8, 60)
                        : new RoutePlan.Maneuver(0, 2, "Continue", "Continue", 8)), 60, "Test");
    }
    @Test public void recordingLocalSourcePreservesExactGeometryGuidanceAndTiming() {
        RoutePlan original = route(true).withRoadMetadata(new double[] {10, 50}, new double[] {40, Double.NaN});
        RoutePlan local = original.withOffline();
        assertFalse(original.offline); assertTrue(local.offline);
        assertSame(local, local.withOffline());
        assertEquals(original.points, local.points); assertEquals(original.maneuvers, local.maneuvers);
        for (double along : new double[] {0, 1, 60, 130, original.lengthMeters()}) {
            assertEquals(original.remainingSecondsAt(along), local.remainingSecondsAt(along), 0);
            assertEquals(original.speedLimitKphAt(along), local.speedLimitKphAt(along), 0);
        }
        assertTrue(local.metadataEnriched);
    }
    @Test public void localSourceDoesNotInventTimingAndSurvivesMetadataCopy() {
        RoutePlan local = route(false).withOffline();
        assertFalse(local.hasTimingProfile()); assertTrue(Double.isNaN(local.remainingSecondsAt(20)));
        RoutePlan copied = local.withRoadMetadata(new double[] {20, 40}, new double[] {Double.NaN, Double.NaN});
        assertTrue(copied.offline); assertTrue(copied.hasTimingProfile());
        assertTrue(Double.isNaN(copied.speedLimitKphAt(20)));
    }
}
