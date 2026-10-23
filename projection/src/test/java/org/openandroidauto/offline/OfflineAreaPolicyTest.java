// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class OfflineAreaPolicyTest {
    @Test public void neighborhoodAtThePublicChurchCoordinatesFitsBothScreenDensities() {
        OfflineAreaPolicy.Bounds bounds=OfflineAreaPolicy.square(47.397, -122.302,2);
        assertEquals(47.397,bounds.latitude(),1e-10);
        assertEquals(-122.302,bounds.longitude(),1e-10);
        assertTrue(OfflineAreaPolicy.estimatedTiles(bounds)>0);
        assertTrue(OfflineAreaPolicy.estimatedTiles(bounds)<OfflineAreaPolicy.MAX_ESTIMATED_TILES);
        OfflineAreaPolicy.requireDownloadable(bounds);
    }
    @Test public void largerSquareCannotEstimateFewerResourcesAtTheSameCenter() {
        long previous=0;
        for(int width:OfflineAreaPolicy.WIDTH_KM) {
            long count=OfflineAreaPolicy.estimatedTiles(OfflineAreaPolicy.square(47.4,-122.3,width));
            assertTrue(count>=previous);previous=count;
        }
    }
    @Test public void highLatitudeRequestIsRejectedWhenTileDensityExceedsTheAllowance() {
        OfflineAreaPolicy.Bounds bounds=OfflineAreaPolicy.square(80,20,20);
        assertTrue(OfflineAreaPolicy.estimatedTiles(bounds)>OfflineAreaPolicy.MAX_ESTIMATED_TILES);
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.requireDownloadable(bounds));
    }
    @Test public void dateLineAndPolesAreRejectedInsteadOfWrappingIntoWorldCoverage() {
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.square(0,179.999,20));
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.square(89,10,1));
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.validate(10,-179,9,179));
    }
    @Test public void nonFiniteAndUnboundedAreasNeverReachTheDownloader() {
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.square(invalid,10,1));
            assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.square(10,invalid,1));
        }
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.square(10,10,1000));
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.validate(60,50,-60,-50));
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.validate(10,10,10,9));
    }
    @Test public void tileBoundaryRoundingStillReservesSurroundingResources() {
        OfflineAreaPolicy.Bounds equator=OfflineAreaPolicy.square(0,0,1);
        assertTrue(OfflineAreaPolicy.estimatedTiles(equator)>=4L*(OfflineAreaPolicy.MAX_ZOOM+1));
        OfflineAreaPolicy.requireDownloadable(equator);
    }
    @Test public void viewportRectangleIsPreservedExactlyInsteadOfExpandedToASquare() {
        double north=47.410375,east=-122.28512,south=47.391515,west=-122.32978;
        OfflineAreaPolicy.Bounds bounds=OfflineAreaPolicy.validate(north,east,south,west);
        assertEquals(north,bounds.north,0);assertEquals(east,bounds.east,0);
        assertEquals(south,bounds.south,0);assertEquals(west,bounds.west,0);
        assertNotEquals(OfflineAreaPolicy.widthKilometers(bounds),OfflineAreaPolicy.heightKilometers(bounds),0.01);
        OfflineAreaPolicy.requireDownloadable(bounds);
    }
    @Test public void viewportCannotExceedEitherSideEvenWhenItsAreaIsNarrow() {
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.validate(47.6,-122.3,47.3,-122.31));
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.validate(47.41,-121.9,47.4,-122.3));
        assertThrows(IllegalArgumentException.class,()->OfflineAreaPolicy.validate(Double.NaN,0,-0.01,-0.01));
        assertEquals(64,OfflineAreaPolicy.MAX_AREAS);
    }
    @Test public void nativeBoundsAcceptOnlyTinySerializationRoundOff() {
        OfflineAreaPolicy.Bounds bounds=OfflineAreaPolicy.validate(47.410375,-122.28512,47.391515,-122.32978);
        assertTrue(OfflineAreaPolicy.matchesNativeBounds(bounds,Math.nextUp(bounds.north),Math.nextDown(bounds.east),bounds.south,bounds.west));
        assertFalse(OfflineAreaPolicy.matchesNativeBounds(bounds,bounds.north+0.000001,bounds.east,bounds.south,bounds.west));
        assertFalse(OfflineAreaPolicy.matchesNativeBounds(bounds,bounds.north,bounds.east+0.001,bounds.south,bounds.west));
        assertFalse(OfflineAreaPolicy.matchesNativeBounds(bounds,Double.NaN,bounds.east,bounds.south,bounds.west));
        assertFalse(OfflineAreaPolicy.matchesNativeBounds(bounds,bounds.south,bounds.east,bounds.north,bounds.west));
    }
}
