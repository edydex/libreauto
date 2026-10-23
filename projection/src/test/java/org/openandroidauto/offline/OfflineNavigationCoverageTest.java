package org.openandroidauto.offline;
import org.junit.Test;
import org.openandroidauto.navigation.GeoPoint;
import java.util.List;
import static org.junit.Assert.*;

public class OfflineNavigationCoverageTest {
    private OfflineAreaPolicy.Bounds box(double west, double east) { return OfflineAreaPolicy.validate(.1,east,0,west); }
    private GeoPoint point(double longitude) { return new GeoPoint(.05,longitude); }
    @Test public void overlappingRectanglesCoverWholeSegment() {
        assertTrue(OfflineNavigationCoverage.covers(List.of(box(0,.1),box(.08,.18)),List.of(point(.01),point(.17))));
    }
    @Test public void vertexCoverageDoesNotHideAGapBetweenAreas() {
        assertFalse(OfflineNavigationCoverage.covers(List.of(box(0,.08),box(.1,.18)),List.of(point(.01),point(.17))));
    }
    @Test public void touchingEdgesFormContinuousCoverage() {
        assertTrue(OfflineNavigationCoverage.covers(List.of(box(0,.09),box(.09,.18)),List.of(point(0),point(.18))));
    }
    @Test public void segmentCannotLeaveCoveredUnionOnDiagonal() {
        assertFalse(OfflineNavigationCoverage.covers(List.of(box(0,.1)),List.of(point(.05),new GeoPoint(.11,.05))));
    }
    @Test public void stationaryRouteAndEmptyCoverageHandled() {
        assertTrue(OfflineNavigationCoverage.covers(List.of(box(0,.1)),List.of(point(.05),point(.05))));
        assertFalse(OfflineNavigationCoverage.covers(List.of(),List.of(point(.05))));
        assertFalse(OfflineNavigationCoverage.covers(List.of(box(0,.1)),List.of()));
    }
}
