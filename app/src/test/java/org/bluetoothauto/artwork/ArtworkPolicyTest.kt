// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.junit.Assert.*
import org.junit.Test
import org.bluetoothauto.navigation.GeoPoint

class ArtworkPolicyTest {
    @Test fun freshAccuratePositionAccepted() { assertTrue(ArtworkPolicy.validPosition(47.0, -122.0, 10.0, 1000, 2000)) }
    @Test fun missingAccuracyIsNotInvented() { assertFalse(ArtworkPolicy.validPosition(47.0, -122.0, Double.NaN, 1000, 2000)) }
    @Test fun oldPositionRejected() { assertFalse(ArtworkPolicy.validPosition(47.0, -122.0, 10.0, 1000, 31001)) }
    @Test fun futurePositionRejected() { assertFalse(ArtworkPolicy.validPosition(47.0, -122.0, 10.0, 3000, 2000)) }
    @Test fun poorPositionRejected() { assertFalse(ArtworkPolicy.validPosition(47.0, -122.0, 101.0, 1000, 2000)) }
    @Test fun invalidCoordinatesRejected() { for (lat in listOf(Double.NaN, Double.POSITIVE_INFINITY, 90.0)) assertFalse(ArtworkPolicy.validPosition(lat, -122.0, 10.0, 1000, 2000)) }
    @Test fun samePlaceDistanceIsZero() { assertEquals(0.0, ArtworkPolicy.meters(47.0, -122.0, 47.0, -122.0), 1e-6) }
    @Test fun datelineUsesShortDistance() { assertTrue(ArtworkPolicy.meters(0.0, 179.9999, 0.0, -179.9999) < 25) }
    @Test fun minimumSnapshotIntervalCoalescesUpdates() { assertEquals(12000, ArtworkPolicy.delay(3000, 15000)) }
    @Test fun elapsedIntervalCanRender() { assertEquals(0, ArtworkPolicy.delay(15001, 15000)) }
    @Test fun negativeAccuracyRejected() { assertFalse(ArtworkPolicy.validPosition(47.0, -122.0, -1.0, 1000, 2000)) }
    @Test fun invalidLongitudeRejected() { assertFalse(ArtworkPolicy.validPosition(47.0, 181.0, 10.0, 1000, 2000)) }
    @Test fun exactFreshnessBoundaryAccepted() { assertTrue(ArtworkPolicy.validPosition(47.0, -122.0, 10.0, 1000, 31000)) }
    @Test fun sameRoadLayerUsesCoordinatesInsteadOfObjectIdentity() {
        val first = listOf(GeoPoint(47.0, -122.0), GeoPoint(47.001, -122.0))
        val second = first.map { GeoPoint(it.latitude, it.longitude) }
        assertTrue(ArtworkPolicy.sameRoadLayer(first, first.last(), second, second.last()))
    }
    @Test fun changedPathOrJunctionRebuildsStaticRoadLayer() {
        val path = listOf(GeoPoint(47.0, -122.0), GeoPoint(47.001, -122.0))
        assertFalse(ArtworkPolicy.sameRoadLayer(path, path.last(), listOf(path.first(), GeoPoint(47.001, -122.001)), path.last()))
        assertFalse(ArtworkPolicy.sameRoadLayer(path, path.last(), path, path.first()))
        assertFalse(ArtworkPolicy.sameRoadLayer(path, path.last(), path.take(1), path.last()))
    }
    @Test fun missingOrRemovedGeometryCannotReuseOldRoadLayer() {
        val point = GeoPoint(47.0, -122.0)
        assertFalse(ArtworkPolicy.sameRoadLayer(null, null, emptyList(), null))
        assertFalse(ArtworkPolicy.sameRoadLayer(listOf(point), point, emptyList(), null))
        assertTrue(ArtworkPolicy.sameRoadLayer(emptyList(), null, emptyList(), null))
    }
}
