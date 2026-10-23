// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.junit.Assert.*
import org.junit.Test

class UpcomingTurnPlannerTest {
    private val now = 20_000L
    private fun route(): RoutePlan {
        val points = listOf(GeoPoint(47.0, -122.0), GeoPoint(47.001, -122.0),
            GeoPoint(47.002, -122.0), GeoPoint(47.002, -121.998), GeoPoint(47.004, -121.998),
            GeoPoint(47.004, -121.996), GeoPoint(47.006, -121.996))
        return RoutePlan(points, listOf(
            RoutePlan.Maneuver(0, 2, "Head north", "", 1),
            RoutePlan.Maneuver(2, 3, "Turn right onto First", "", 10),
            RoutePlan.Maneuver(3, 4, "Turn left onto Second", "", 15),
            RoutePlan.Maneuver(4, 5, "Turn right onto Third", "", 10),
            RoutePlan.Maneuver(5, 6, "Turn left onto Fourth", "", 15)), 180.0, "Synthetic destination")
    }
    private fun snapshot(route: RoutePlan = route(), latitude: Double = 47.0005,
                         longitude: Double = -122.0, accuracy: Double = 3.0,
                         timestamp: Long = now): OwnNavigation.Snapshot {
        val progress = RouteTracker(route).update(GeoPoint(latitude, longitude), accuracy, timestamp)
        return OwnNavigation.Snapshot(route = route, progress = progress,
            position = Position(latitude, longitude, accuracy = accuracy.toFloat(), receivedAt = timestamp))
    }
    private fun plan(snapshot: OwnNavigation.Snapshot, count: Int = 3) = UpcomingTurnPlanner.plan(snapshot, count, now)

    @Test fun currentAndTwoFutureTurnsKeepRouteOrder() {
        assertEquals(listOf("Turn right onto First", "Turn left onto Second", "Turn right onto Third"),
            plan(snapshot()).map { it.guidance.instruction })
    }
    @Test fun requestedCountIsClampedToThree() {
        assertTrue(plan(snapshot(), -1).isEmpty())
        assertTrue(plan(snapshot(), 0).isEmpty())
        assertEquals(1, plan(snapshot(), 1).size)
        assertEquals(3, plan(snapshot(), Int.MAX_VALUE).size)
    }
    @Test fun currentTurnReusesProductionGeometryExactly() {
        val snapshot = snapshot()
        val expected = requireNotNull(OwnTurnGeometry.from(snapshot, now))
        val actual = plan(snapshot, 1).single().geometry
        assertEquals(expected.key, actual.key)
        assertSame(expected.junction, actual.junction)
        assertEquals(expected.points.map { it.latitude to it.longitude }, actual.points.map { it.latitude to it.longitude })
        assertEquals(expected.approachSpeedLimitKph, actual.approachSpeedLimitKph)
    }
    @Test fun actualCurrentPositionIsNotReplacedByAFutureFix() {
        val snapshot = snapshot()
        for (turn in plan(snapshot)) {
            assertSame(snapshot.position, turn.position)
            assertEquals(snapshot.position!!.receivedAt, turn.guidance.receivedAt)
            assertEquals("Own maps", turn.guidance.source)
            assertFalse(turn.guidance.demo)
        }
    }
    @Test fun futureDistancesFollowExactRouteLength() {
        val snapshot = snapshot(); val route = snapshot.route!!; val progress = snapshot.progress!!
        val turns = plan(snapshot)
        for (index in turns.indices) {
            val maneuver = route.maneuvers[progress.maneuverIndex + index]
            assertEquals(route.cumulativeMeters[maneuver.beginIndex] - progress.alongMeters,
                turns[index].guidance.distanceMeters!!, 0.0001)
            assertSame(route.points[maneuver.beginIndex], turns[index].geometry.junction)
            assertTrue(turns[index].geometry.points.any { it.latitude == turns[index].geometry.junction.latitude &&
                it.longitude == turns[index].geometry.junction.longitude })
        }
    }
    @Test fun futureTurnKeysMatchTheirLaterProductionSelection() {
        val route = route()
        val first = plan(snapshot(route))
        val later = snapshot(route, latitude = 47.002, longitude = -121.9995)
        val active = requireNotNull(OwnTurnGeometry.from(later, now))
        assertEquals(first[1].geometry.key, active.key)
    }
    @Test fun movementDoesNotInvalidateSameFutureTurnKeys() {
        val route = route()
        assertEquals(plan(snapshot(route)).map { it.geometry.key },
            plan(snapshot(route, latitude = 47.001)).map { it.geometry.key })
    }
    @Test fun metadataRefreshKeepsKeysButUsesFutureApproachLimits() {
        val route = route()
        val enriched = route.withRoadMetadata(null, doubleArrayOf(90.0, 80.0, 30.0, 40.0, 50.0, 60.0))
        val before = plan(snapshot(route)); val after = plan(snapshot(enriched))
        assertEquals(before.map { it.geometry.key }, after.map { it.geometry.key })
        assertEquals(90.0, after[0].geometry.approachSpeedLimitKph!!, 0.0)
        assertEquals(30.0, after[1].geometry.approachSpeedLimitKph!!, 0.0)
        assertEquals(40.0, after[2].geometry.approachSpeedLimitKph!!, 0.0)
    }
    @Test fun unlimitedAndUnknownFutureLimitsAreNotInvented() {
        val route = route().withRoadMetadata(null, doubleArrayOf(90.0, 80.0,
            Double.POSITIVE_INFINITY, Double.NaN, 50.0, 60.0))
        val turns = plan(snapshot(route))
        assertNull(turns[1].geometry.approachSpeedLimitKph)
        assertNull(turns[2].geometry.approachSpeedLimitKph)
    }
    @Test fun rerouteWithNewGeometryObjectsDoesNotReuseOldKeys() {
        assertNotEquals(plan(snapshot(route())).map { it.geometry.key },
            plan(snapshot(route())).map { it.geometry.key })
    }
    @Test fun unsafeAndReroutingSnapshotsProduceNoPreparation() {
        val snapshot = snapshot()
        assertTrue(plan(snapshot.copy(routing = true)).isEmpty())
        assertTrue(plan(snapshot.copy(route = null)).isEmpty())
        assertTrue(plan(snapshot.copy(progress = null)).isEmpty())
        assertTrue(plan(snapshot.copy(position = null)).isEmpty())
        assertTrue(plan(snapshot.copy(position = snapshot.position!!.copy(demo = true))).isEmpty())
        assertTrue(plan(snapshot.copy(position = snapshot.position!!.copy(receivedAt = now - 30_001))).isEmpty())
        assertTrue(plan(snapshot(accuracy = 40.0)).isEmpty())
        assertTrue(plan(snapshot(longitude = -121.99)).isEmpty())
    }
    @Test fun googleNotificationIsNotAFullRouteSource() {
        val snapshot = snapshot().copy(guidance = Guidance("Turn right", source = "Google Maps"))
        assertTrue(plan(snapshot).isEmpty())
    }
    @Test fun arrivedRouteDoesNotPrepareFuturePictures() {
        assertTrue(plan(snapshot(latitude = 47.006, longitude = -121.996)).isEmpty())
    }
    @Test fun planningDoesNotMutateLiveProgressOrRoute() {
        val snapshot = snapshot(); val progress = snapshot.progress!!; val route = snapshot.route!!
        val distances = route.cumulativeMeters.clone()
        val selected = progress.maneuverIndex; val along = progress.alongMeters
        plan(snapshot)
        assertSame(progress, snapshot.progress)
        assertSame(route, snapshot.route)
        assertEquals(selected, progress.maneuverIndex)
        assertEquals(along, progress.alongMeters, 0.0)
        assertArrayEquals(distances, route.cumulativeMeters, 0.0)
    }
    @Test fun denseFutureShapesRemainBounded() {
        val points = (0..4000).map { GeoPoint(47.0 + it * 0.00001, -122.0) } +
            (1..100).map { GeoPoint(47.04, -122.0 + it * 0.00001) } +
            (1..100).map { GeoPoint(47.04 + it * 0.00001, -121.999) }
        val route = RoutePlan(points, listOf(RoutePlan.Maneuver(0, 4000, "Head north", "", 1),
            RoutePlan.Maneuver(4000, 4100, "Turn right", "", 10),
            RoutePlan.Maneuver(4100, 4200, "Turn left", "", 15)), 500.0, "Synthetic destination")
        val turns = plan(snapshot(route))
        assertEquals(2, turns.size)
        assertTrue(turns.all { it.geometry.points.size <= 256 })
    }
}
