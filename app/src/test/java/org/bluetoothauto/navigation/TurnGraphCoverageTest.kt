// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.junit.Assert.*
import org.junit.Test

class TurnGraphCoverageTest {
    private val center = GeoPoint(0.0, 0.0)
    private fun east(meters: Double) = GeoPoint(0.0, meters / 111195.08)
    private fun match(points: List<GeoPoint>, junction: GeoPoint = points[1]) =
        TurnMatcher.Match(points, junction, null, "fixture")

    @Test fun anInteriorOriginCanReusePartialCoverageWithoutClaimingAFullSearchCircle() {
        val origin = east(700.0)
        assertTrue(TurnGraphCoverage.reusableInterior(center, 1000.0, origin))
        assertFalse(TurnGraphCoverage.full(center, 1000.0, origin, 500.0))
        assertTrue(TurnGraphCoverage.containsMatch(center, 1000.0, match(listOf(origin, east(850.0), east(950.0)))))
    }
    @Test fun originRequiresFiftyMetersOfKnownAreaClearance() {
        assertTrue(TurnGraphCoverage.reusableInterior(center, 1000.0, east(949.0)))
        assertFalse(TurnGraphCoverage.reusableInterior(center, 1000.0, east(951.0)))
    }
    @Test fun aPathLeavingTheKnownAreaIsRejectedEvenWhenItsJunctionIsInside() {
        assertFalse(TurnGraphCoverage.containsMatch(center, 1000.0,
            match(listOf(east(700.0), east(850.0), east(1001.0)))))
    }
    @Test fun renderedPathRequiresTenMetersOfClearance() {
        assertTrue(TurnGraphCoverage.containsMatch(center, 1000.0,
            match(listOf(east(700.0), east(850.0), east(989.0)))))
        assertFalse(TurnGraphCoverage.containsMatch(center, 1000.0,
            match(listOf(east(700.0), east(850.0), east(991.0)))))
    }
    @Test fun theJunctionIsCheckedSeparatelyFromThePolyline() {
        assertFalse(TurnGraphCoverage.containsMatch(center, 1000.0,
            match(listOf(east(700.0), east(850.0)), east(1001.0))))
    }
    @Test fun invalidOrTinyCoverageCannotAuthorizeAPath() {
        val path = match(listOf(center, east(1.0)))
        assertFalse(TurnGraphCoverage.containsMatch(center, Double.NaN, path))
        assertFalse(TurnGraphCoverage.containsMatch(center, 10.0, path))
        assertFalse(TurnGraphCoverage.reusableInterior(center, -1.0, center))
    }
}
