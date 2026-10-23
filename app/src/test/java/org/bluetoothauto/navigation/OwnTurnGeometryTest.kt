// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Position
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class OwnTurnGeometryTest {
    private val now = 10_000L
    private fun route() = RoutePlan(listOf(GeoPoint(47.0, -122.0), GeoPoint(47.001, -122.0),
        GeoPoint(47.002, -122.0), GeoPoint(47.002, -121.998), GeoPoint(47.002, -121.996)),
        listOf(RoutePlan.Maneuver(0, 2, "Head north", "", 1),
            RoutePlan.Maneuver(2, 4, "Turn right", "", 10)), 60.0, "Destination")
    private fun snapshot(route: RoutePlan, latitude: Double = 47.0005, longitude: Double = -122.0,
                         accuracy: Double = 3.0, receivedAt: Long = now): OwnNavigation.Snapshot {
        val progress = RouteTracker(route).update(GeoPoint(latitude, longitude), accuracy, receivedAt)
        return OwnNavigation.Snapshot(route = route, progress = progress,
            position = Position(latitude, longitude, accuracy = accuracy.toFloat(), receivedAt = receivedAt))
    }

    @Test fun exactTurnAndCurvedApproachAreRetained() {
        val route = route()
        val turn = requireNotNull(OwnTurnGeometry.from(snapshot(route), now))
        assertEquals(47.002, turn.junction.latitude, 0.0)
        assertEquals(-122.0, turn.junction.longitude, 0.0)
        assertTrue(turn.points.any { it.latitude == 47.001 && it.longitude == -122.0 })
        assertTrue(turn.points.any { it.latitude == turn.junction.latitude && it.longitude == turn.junction.longitude })
        assertTrue(turn.points.last().longitude > turn.junction.longitude)
        assertEquals(80.0, turn.junction.distanceTo(turn.points.last()), 1.0)
        assertEquals("exact-route", turn.confidence)
        assertSame(route, turn.sourceRoute)
        assertNull(turn.artworkFocus)
    }

    @Test fun keyIsStableAsTheCarApproachesSameTurn() {
        val route = route()
        val first = requireNotNull(OwnTurnGeometry.from(snapshot(route), now))
        val closer = requireNotNull(OwnTurnGeometry.from(snapshot(route, 47.001), now))
        assertEquals(first.key, closer.key)
        assertTrue(first.points.first().latitude < closer.points.first().latitude)
    }

    @Test fun unknownAndUnlimitedLimitsAreNotInvented() {
        assertNull(OwnTurnGeometry.from(snapshot(route()), now)?.approachSpeedLimitKph)
        val route = route().withRoadMetadata(null, doubleArrayOf(Double.POSITIVE_INFINITY, Double.NaN, Double.NaN, Double.NaN))
        assertNull(OwnTurnGeometry.from(snapshot(route), now)?.approachSpeedLimitKph)
    }

    @Test fun postedLimitComesFromActualCurrentSegment() {
        val route = route().withRoadMetadata(null, doubleArrayOf(40.0, 50.0, 30.0, 25.0))
        assertEquals(40.0, requireNotNull(OwnTurnGeometry.from(snapshot(route), now)).approachSpeedLimitKph!!, 0.0)
        assertEquals(50.0, requireNotNull(OwnTurnGeometry.from(snapshot(route, 47.0015), now)).approachSpeedLimitKph!!, 0.0)
    }

    @Test fun metadataOnlyRefreshPreservesTurnIdentity() {
        val original = route()
        val enriched = original.withRoadMetadata(null, doubleArrayOf(40.0, 50.0, 30.0, 25.0))
        assertEquals(requireNotNull(OwnTurnGeometry.from(snapshot(original), now)).key,
            requireNotNull(OwnTurnGeometry.from(snapshot(enriched), now)).key)
    }

    @Test fun unsafeOrStaleSnapshotsHaveNoAssertedTurn() {
        val route = route(); val good = snapshot(route)
        assertNull(OwnTurnGeometry.from(good.copy(routing = true), now))
        assertNull(OwnTurnGeometry.from(good.copy(position = null), now))
        assertNull(OwnTurnGeometry.from(good.copy(position = good.position!!.copy(demo = true)), now))
        assertNull(OwnTurnGeometry.from(snapshot(route, receivedAt = 1), now + 30_001))
        assertNull(OwnTurnGeometry.from(snapshot(route, accuracy = 40.0), now))
        assertNull(OwnTurnGeometry.from(snapshot(route, longitude = -121.99), now))
    }

    @Test fun denseLongApproachIsShortenedWithoutSamplingTheExactDeparture() {
        val points = (0..4000).map { GeoPoint(47.0 + it * 0.00001, -122.0) } +
            (1..100).map { GeoPoint(47.04, -122.0 + it * 0.00001) }
        val route = RoutePlan(points, listOf(RoutePlan.Maneuver(0, 4000, "Head north", "", 1),
            RoutePlan.Maneuver(4000, points.lastIndex, "Turn right", "", 10)), 500.0, "Destination")
        val turn = requireNotNull(OwnTurnGeometry.from(snapshot(route, 47.0005), now))
        assertTrue(turn.points.size <= 256)
        assertTrue(turn.points.any { it.latitude == 47.04 && it.longitude == -122.0 })
        assertTrue(turn.points.first().distanceTo(turn.junction) <= 1501)
    }

    private fun curve(radius: Double = 600.0, type: Int = 18, subdivisions: Int = 90): Pair<RoutePlan,GeoPoint> {
        fun point(east:Double,north:Double)=GeoPoint(47.0+north/111_195.08,
            -122.0+east/(111_195.08*cos(Math.toRadians(47.0))))
        val curve=(0..subdivisions).map { step ->
            val angle=Math.PI/2*step/subdivisions
            point(radius*(1-cos(angle)),radius*sin(angle))
        }
        val points=listOf(point(0.0,-100.0))+curve+point(radius+1500,radius)
        return RoutePlan(points,listOf(RoutePlan.Maneuver(0,1,"Start", "",1),
            RoutePlan.Maneuver(1,points.lastIndex,"Provider maneuver", "",type),
            RoutePlan.Maneuver(points.lastIndex,points.lastIndex,"Arrive", "",4)),100.0,"Curve fixture") to curve.last()
    }
    private fun atStart(route:RoutePlan)=snapshot(route,route.points.first().latitude,route.points.first().longitude)

    @Test fun longProviderRampRetainsCompleteCurveAndSettlingContextWithoutParsingText() {
        val (route,lastCurve)=curve()
        val turn=checkNotNull(OwnTurnGeometry.from(atStart(route),now))
        assertTrue(turn.points.any { it.distanceTo(lastCurve)<.01 })
        assertTrue(turn.points.last().distanceTo(lastCurve)>150)
        val original=route.points.filter { point -> route.cumulativeMeters[route.points.indexOf(point)]<=
            route.cumulativeMeters[route.points.indexOf(lastCurve)] }
        assertTrue(original.all { p -> turn.points.any { it.distanceTo(p)<.01 } })
    }

    @Test fun longRampStopsAtNextMeaningfulDecisionBeforeDrawingItsOutgoingBranch() {
        val (original,_)=curve()
        val decision=50
        val route=RoutePlan(original.points,listOf(RoutePlan.Maneuver(0,1,"Start","",1),
            RoutePlan.Maneuver(1,decision,"Ramp","",18),
            RoutePlan.Maneuver(decision,original.points.lastIndex,"Turn right","",10),
            RoutePlan.Maneuver(original.points.lastIndex,original.points.lastIndex,"Arrive","",4)),100.0,"Fixture")
        val turn=checkNotNull(OwnTurnGeometry.from(atStart(route),now))
        assertTrue(turn.points.last().distanceTo(route.points[decision])<.01)
    }

    @Test fun highwayCurveCannotExceedTwoKilometresOfDeparture() {
        val (route,_)=curve(radius=2000.0)
        val turn=checkNotNull(OwnTurnGeometry.from(atStart(route),now))
        val junction=turn.points.indexOfFirst { it.distanceTo(turn.junction)<.01 }
        assertEquals(2000.0,turn.points.drop(junction).zipWithNext { a,b -> a.distanceTo(b) }.sum(),.1)
    }

    @Test fun straightProviderRampKeepsCompactEightyMeterContext() {
        val points=listOf(GeoPoint(47.0,-122.0),GeoPoint(47.001,-122.0),GeoPoint(47.02,-122.0))
        val route=RoutePlan(points,listOf(RoutePlan.Maneuver(0,1,"Start","",1),
            RoutePlan.Maneuver(1,2,"Unparsed provider text","",18)),100.0,"Straight")
        val turn=checkNotNull(OwnTurnGeometry.from(atStart(route),now))
        assertEquals(80.0,turn.junction.distanceTo(turn.points.last()),.1)
    }

    @Test fun broadOrdinaryStreetCornerMayExtendPastEightyMetersWithinFiveHundred() {
        val (route,lastCurve)=curve(radius=180.0,type=10)
        val turn=checkNotNull(OwnTurnGeometry.from(atStart(route),now))
        assertTrue(turn.points.any { it.distanceTo(lastCurve)<.01 })
        val junction=turn.points.indexOfFirst { it.distanceTo(turn.junction)<.01 }
        val departure=turn.points.drop(junction).zipWithNext { a,b -> a.distanceTo(b) }.sum()
        assertTrue(departure in 280.0..500.1)
    }

    @Test fun overlyDenseDepartureUsesArrowFallbackRatherThanShortcuttingCurveVertices() {
        val (route,_)=curve(subdivisions=1000)
        assertNull(OwnTurnGeometry.from(atStart(route),now))
    }

    private fun joinedRamp(firstLabel: String = "I 5", followingLabel: String = "I 5 North",
                           joinedLabel: String = "I 5 North", followingType: Int = 23,
                           firstType: Int = 23, closeExit: Boolean = false): RoutePlan {
        fun point(east: Double, north: Double) = GeoPoint(47.0 + north / 111_195.08,
            -122.0 + east / (111_195.08 * cos(Math.toRadians(47.0))))
        val points = listOf(point(0.0, -100.0), point(0.0, 0.0), point(0.0, 300.0),
            point(0.0, 600.0), point(20.0, 650.0), point(60.0, 700.0), point(120.0, 740.0),
            point(200.0, 760.0), point(if (closeExit) 250.0 else 500.0, 760.0),
            point(600.0, 900.0), point(600.0, 1200.0))
        fun step(begin: Int, end: Int, type: Int, label: String) = RoutePlan.Maneuver(begin, end,
            "Provider text is not parsed", "", type, 10.0, listOf(label), emptyList(), listOf(label))
        return RoutePlan(points, listOf(step(0, 1, 1, "SR 18 West"),
            step(1, 3, firstType, firstLabel), step(3, if (closeExit) 8 else 10, followingType, followingLabel),
            step(if (closeExit) 8 else 10, 10, 20, "Exit")), 100.0, "Joined motorway fixture")
            .withRoadSpans(listOf(RoutePlan.RoadSpan(0, 1, listOf("SR 18 West"), "drive", "road"),
                RoutePlan.RoadSpan(1, 3, emptyList(), "drive", "ramp"),
                RoutePlan.RoadSpan(3, 7, emptyList(), "drive", "ramp"),
                RoutePlan.RoadSpan(7, 10, listOf(joinedLabel), "drive", "road")))
    }

    @Test fun chainedSameHighwayForkRetainsWholeRampAndActualJoinedMainline() {
        val route = joinedRamp()
        val turn = checkNotNull(OwnTurnGeometry.from(atStart(route), now))
        assertSame(route, turn.sourceRoute)
        assertSame(route.points[1], turn.junction)
        assertSame(route.points[3], turn.artworkFocus)
        assertTrue(turn.key.endsWith(":join:7"))
        assertTrue(route.points.subList(1, 8).all { p -> turn.points.any { it.distanceTo(p) < .01 } })
        assertEquals(150.0, route.points[7].distanceTo(turn.points.last()), .1)
        // The next bend on the joined motorway is not part of this interchange picture.
        assertFalse(turn.points.any { it.distanceTo(route.points[9]) < .01 })
    }

    @Test fun sameHighwayMergeMayFinishRampWithoutMovingFocusToAnInventedFork() {
        val route = joinedRamp(followingType = 25)
        val turn = checkNotNull(OwnTurnGeometry.from(atStart(route), now))
        assertTrue(turn.key.endsWith(":join:7"))
        assertNull(turn.artworkFocus)
        assertEquals(150.0, route.points[7].distanceTo(turn.points.last()), .1)
    }

    @Test fun unrelatedHighwayForkAndOppositeSignedDirectionRemainSeparate() {
        for (route in listOf(joinedRamp(followingLabel = "I 405 North"),
            joinedRamp(followingLabel = "I 5 South"),
            joinedRamp(firstLabel = "I 5 North", followingLabel = "I 5 South"),
            joinedRamp(firstLabel = "I 5 North", joinedLabel = "I 5 South"))) {
            val turn = checkNotNull(OwnTurnGeometry.from(atStart(route), now))
            assertNull(turn.artworkFocus)
            assertFalse(turn.key.contains(":join:"))
            assertTrue(turn.points.none { it.distanceTo(route.points[7]) < .01 })
        }
    }

    @Test fun streetTurnAndUnsignedForkDoNotAcquireJoinedRampSemantics() {
        for (route in listOf(joinedRamp(firstType = 10), joinedRamp(firstLabel = "Pine Street"))) {
            val turn = checkNotNull(OwnTurnGeometry.from(atStart(route), now))
            assertNull(turn.artworkFocus)
            assertFalse(turn.key.contains(":join:"))
        }
    }

    @Test fun joinedMainlineContextStopsAtTheNextExitDecision() {
        val route = joinedRamp(closeExit = true)
        val turn = checkNotNull(OwnTurnGeometry.from(atStart(route), now))
        assertTrue(turn.key.endsWith(":join:7"))
        assertTrue(turn.points.last().distanceTo(route.points[8]) < .01)
        assertTrue(turn.points.none { it.distanceTo(route.points[9]) < .01 })
    }

    @Test fun roadMetadataCanExtendRampWindowWithoutReusingItsOldPictureIdentity() {
        val enriched = joinedRamp()
        val basic = RoutePlan(enriched.points, enriched.maneuvers, enriched.seconds, enriched.destinationName)
        val before = checkNotNull(OwnTurnGeometry.from(atStart(basic), now))
        val after = checkNotNull(OwnTurnGeometry.from(atStart(enriched), now))
        assertFalse(before.key.contains(":join:"))
        assertTrue(after.key.endsWith(":join:7"))
        assertNotEquals(before.key, after.key)
        assertSame(enriched, after.sourceRoute)
    }

    @Test fun datelineApproachUsesTheShortLongitudePath() {
        val points = listOf(GeoPoint(0.0, 179.999), GeoPoint(0.0, -179.999), GeoPoint(.001, -179.999))
        val route = RoutePlan(points, listOf(RoutePlan.Maneuver(0, 1, "Head east", "", 1),
            RoutePlan.Maneuver(1, 2, "Turn left", "", 10)), 30.0, "Destination")
        val turn = requireNotNull(OwnTurnGeometry.from(snapshot(route, 0.0, 179.9995), now))
        assertTrue(turn.points.all { kotlin.math.abs(it.longitude) > 179.99 })
    }
}
