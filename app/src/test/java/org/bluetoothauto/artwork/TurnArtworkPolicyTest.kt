// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import org.bluetoothauto.navigation.GeoPoint
import org.bluetoothauto.navigation.RoutePlan
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TurnArtworkPolicyTest {
    private val junction = GeoPoint(47.6062, -122.3321)
    private val points = listOf(GeoPoint(47.6032, -122.3321), junction, GeoPoint(47.6062, -122.3281))
    private fun geometry(limit: Double? = 40.0, key: String = "turn-1") = TurnGeometry(points, junction, limit, "route", key)
    private fun position(latitude: Double = 47.6059, longitude: Double = -122.3321, speed: Float? = null) =
        Position(latitude, longitude, accuracy = 5f, receivedAt = 1000, speedMps = speed)

    @Test fun lowerPostedRoadSpeedGivesCloserPreferredView() {
        assertTrue(TurnArtworkPolicy.preferredZoom(30.0, 25.0) > TurnArtworkPolicy.preferredZoom(110.0, 0.0))
    }
    @Test fun postedSpeedSizesApproachWithoutForcingTheOldDistantHighwayScale() {
        val short = geometry().copy(points = listOf(points.first(), junction, offset(26.0, 0.0)))
        val slow = TurnArtworkPolicy.frame(position(47.55, -122.4), short.copy(approachSpeedLimitKph = 30.0))
        val city = TurnArtworkPolicy.frame(position(47.55, -122.4), short.copy(approachSpeedLimitKph = 50.0))
        val highway = TurnArtworkPolicy.frame(position(47.55, -122.4), short.copy(approachSpeedLimitKph = 110.0))
        assertTrue(slow.zoom >= 17.5)
        assertTrue(slow.zoom - city.zoom >= 0.3)
        assertTrue(city.zoom > highway.zoom)
        assertTrue(highway.zoom > 16.0)
        assertTrue(highway.path.first().distanceTo(junction) in 119.0..121.0)
        assertVisible(slow, slow.path + junction)
    }
    @Test fun postedLimitTakesPriorityOverPhoneSpeed() {
        assertEquals(17.7, TurnArtworkPolicy.preferredZoom(30.0, 30.0), 0.0)
    }
    @Test fun unknownSpeedHasUsefulCloseDefault() {
        assertEquals(16.8, TurnArtworkPolicy.preferredZoom(null, null), 0.0)
        assertEquals(16.8, TurnArtworkPolicy.preferredZoom(Double.NaN, -1.0), 0.0)
    }
    @Test fun fallbackCameraUsesRealPhonePointWithoutAnyPath() {
        val fix = position(speed = 0f); val frame = TurnArtworkPolicy.frame(fix, null)
        assertEquals(fix.latitude, frame.center.latitude, 0.0)
        assertEquals(fix.longitude, frame.center.longitude, 0.0)
        assertTrue(frame.path.isEmpty()); assertNull(frame.junction); assertNull(frame.geometryKey)
    }
    @Test fun realJunctionKeepsBoundedApproachAndEntireSuppliedDeparture() {
        val frame = TurnArtworkPolicy.frame(position(), geometry())
        assertEquals("turn-1", frame.geometryKey)
        assertTrue(frame.path.size in 3..512)
        assertTrue(frame.path.first().distanceTo(junction) in 44.0..46.0)
        assertSame(points.last(), frame.path.last())
        assertEquals(junction.longitude, frame.path.first().longitude, 1e-9)
        assertEquals(junction.latitude, frame.path.last().latitude, 1e-9)
    }
    @Test fun confirmedTurnAndSameRoadContextDoNotDrawGpsLikeJunctionDots() {
        val normal=TurnArtworkPolicy.frame(position(),geometry())
        val context=TurnArtworkPolicy.frame(position(),geometry().copy(confidence="google-route-context"))
        assertFalse(normal.markJunction);assertFalse(context.markJunction)
        assertEquals(normal.path.size,context.path.size)
        normal.path.zip(context.path).forEach{(a,b)->assertTrue(a.distanceTo(b)<0.001)}
        assertEquals(normal.bearingDegrees,context.bearingDegrees,0.0)
    }
    @Test fun cameraFitsCompleteDepartureWhileNativeSourceKeepsEntireValidatedPath() {
        val frame = TurnArtworkPolicy.frame(position(), geometry())
        assertEquals(points, frame.drawPath)
        assertNotEquals(frame.path, frame.drawPath)
        assertTrue(frame.path.first().distanceTo(junction)<50)
        assertSame(points.last(), frame.path.last())
        assertVisible(frame, frame.path + junction)
    }
    @Test fun fullDrawPathNeverAddsOrExtrapolatesUnvalidatedCoordinates() {
        val supplied=geometry()
        val frame=TurnArtworkPolicy.frame(position(),supplied)
        assertSame(supplied.points,frame.drawPath)
        assertEquals(supplied.points.size,frame.drawPath.size)
        assertTrue(frame.drawPath.first().distanceTo(junction)>frame.path.first().distanceTo(junction))
        assertSame(frame.drawPath.last(), frame.path.last())
        val context=CurrentContextPolicy.frame(position(),1_000)!!
        assertTrue(context.drawPath.isEmpty());assertNull(context.junction)
    }
    @Test fun distantPhoneDoesNotCenterMapOnWrongPlace() {
        val frame = TurnArtworkPolicy.frame(position(47.55, -122.4), geometry())
        assertFalse(frame.includesPosition)
        assertTrue(frame.center.distanceTo(junction) < 250.0)
        assertTrue(frame.zoom > 15.0)
    }
    @Test fun nearbyPhoneDoesNotChangeTheTurnOnlyCamera() {
        val frame = TurnArtworkPolicy.frame(position(), geometry())
        assertFalse(frame.includesPosition)
        val far=TurnArtworkPolicy.frame(position(47.55,-122.4,speed=30f),geometry())
        assertEquals(frame.center.latitude,far.center.latitude,0.0)
        assertEquals(frame.center.longitude,far.center.longitude,0.0)
        assertEquals(frame.zoom,far.zoom,0.0)
        assertVisible(frame,frame.path+junction)
    }
    @Test fun changingTurnIdentityCannotReusePreviousMap() {
        val first = TurnArtworkPolicy.frame(position(), geometry())
        val second = TurnArtworkPolicy.frame(position(), geometry(key = "turn-2"))
        assertFalse(first.compatible(second))
    }
    @Test fun distanceOnlyUpdateCanKeepSameTurnCamera() {
        val first = TurnArtworkPolicy.frame(position(), geometry())
        val second = TurnArtworkPolicy.frame(position(47.60592), geometry())
        assertTrue(first.compatible(second))
    }
    @Test fun proposedCameraMotionDefersTurnVisibilityToActualCachedProjection() {
        val frame = TurnArtworkPolicy.frame(position(), geometry())
        val movedCamera = frame.copy(center = offset(55.0, 0.0))
        assertTrue(frame.compatible(movedCamera))
        assertTrue(frame.compatibleInFlight(movedCamera))
        assertFalse(frame.compatibleInFlight(frame.copy(center = offset(400.0, 0.0))))
    }
    @Test fun cachedFallbackDefersGeographicVisibilityToActualSnapshotProjection() {
        val first = TurnArtworkPolicy.frame(position(), null)
        assertTrue(first.compatible(TurnArtworkPolicy.frame(position(47.6061), null)))
        assertTrue(first.compatible(TurnArtworkPolicy.frame(position(47.6080), null)))
    }
    @Test fun movingFallbackMayShareBoundedInFlightSnapshotWithoutStarvingIt() {
        val first = TurnArtworkPolicy.frame(position(), null)
        assertTrue(first.compatibleInFlight(TurnArtworkPolicy.frame(position(47.6061), null)))
        assertFalse(first.compatibleInFlight(TurnArtworkPolicy.frame(position(47.6080), null)))
    }
    @Test fun changedZoomStillInvalidatesFallbackMap() {
        val first = TurnArtworkPolicy.frame(position(speed = 0f), null)
        assertFalse(first.compatible(TurnArtworkPolicy.frame(position(speed = 30f), null)))
    }
    @Test fun unrelatedJunctionCannotBeInventedOnRoad() {
        assertFalse(TurnArtworkPolicy.validGeometry(geometry().copy(junction = GeoPoint(47.7, -122.4))))
        assertNull(TurnArtworkPolicy.frame(position(), geometry().copy(junction = GeoPoint(47.7, -122.4))).geometryKey)
    }
    @Test fun geometryResourceAndIdentityBoundsEnforced() {
        assertFalse(TurnArtworkPolicy.validGeometry(geometry().copy(points = List(513) { junction })))
        assertFalse(TurnArtworkPolicy.validGeometry(geometry().copy(points = listOf(junction, junction))))
        assertFalse(TurnArtworkPolicy.validGeometry(geometry().copy(confidence = "")))
        assertFalse(TurnArtworkPolicy.validGeometry(geometry(key = "")))
    }
    @Test fun datelineJunctionUsesNearbyCameraInsteadOfWholeWorld() {
        val center = GeoPoint(0.0, 179.9999)
        val geometry = TurnGeometry(listOf(GeoPoint(0.0, 179.998), center, GeoPoint(0.0, -179.999)), center, 30.0, "route", "dateline")
        val frame = TurnArtworkPolicy.frame(position(0.0, 179.9995), geometry)
        assertTrue(abs(frame.center.longitude) > 179.99)
        assertTrue(frame.zoom >= 16.5)
        assertVisible(frame, frame.path + center)
    }
    @Test fun highwayFitsWiderRealRoadWindow() {
        val local = TurnArtworkPolicy.frame(position(), geometry(30.0))
        val highway = TurnArtworkPolicy.frame(position(), geometry(110.0))
        assertTrue(highway.zoom <= local.zoom)
        assertTrue(highway.path.first().distanceTo(junction) > local.path.first().distanceTo(junction))
        assertVisible(highway, highway.path + junction)
    }
    @Test fun unknownLimitTurnCameraDoesNotChangeWithGpsSpeedOrHeading() {
        val first=TurnArtworkPolicy.frame(position(speed=0f),geometry(null))
        val moving=TurnArtworkPolicy.frame(position(47.55,-122.4,speed=30f).copy(bearing=270f),geometry(null))
        assertEquals(first.center.latitude,moving.center.latitude,0.0)
        assertEquals(first.center.longitude,moving.center.longitude,0.0)
        assertEquals(first.zoom,moving.zoom,0.0)
        assertEquals(first.bearingDegrees,moving.bearingDegrees,0.0)
    }
    @Test fun unknownPostedLimitHighwayExitFramesApproachAndDepartureInsteadOfInventingSpeed() {
        val supplied=geometry(null)
        val local=TurnArtworkPolicy.frame(position(),supplied)
        val highway=TurnArtworkPolicy.frame(position(),supplied,
            "Use the right lane to take the I-5 N exit toward Seattle")
        assertTrue(highway.path.first().distanceTo(junction) in 119.0..121.0)
        assertSame(supplied.points.last(),highway.path.last())
        assertNull(supplied.approachSpeedLimitKph)
        assertSame(supplied.points,highway.drawPath)
        assertEquals(local.bearingDegrees,highway.bearingDegrees,0.0)
        assertFalse(highway.markJunction)
        assertJunctionSafelyVisible(highway)
        assertVisible(highway,highway.path+junction)
    }
    @Test fun highwayRampKeepAndMergeCuesAlsoReceiveCompleteAdaptiveFraming() {
        for(instruction in listOf("Take the I-5 N ramp","Keep right to take I-5 N","Merge onto I-5 N")) {
            val highway=TurnArtworkPolicy.frame(position(),geometry(null),instruction)
            assertTrue(instruction, highway.path.first().distanceTo(junction) in 119.0..121.0)
            assertJunctionSafelyVisible(highway)
            assertVisible(highway, highway.path)
        }
    }
    @Test fun highwayCameraHintRequiresBothSupportedManeuverAndExplicitRoadReference() {
        val expected=TurnArtworkPolicy.frame(position(),geometry(null))
        for(instruction in listOf("Keep right onto Pine Street","Turn right onto I-5 N","Drive toward I-5 N")) {
            val actual=TurnArtworkPolicy.frame(position(),geometry(null),instruction)
            assertSameFraming(expected,actual)
        }
    }
    @Test fun realPostedLimitKeepsItsApproachContextForHighwayManeuver() {
        val supplied=geometry(30.0)
        val actual=TurnArtworkPolicy.frame(position(),supplied,"Take the I-5 N exit on the right")
        assertTrue(actual.path.first().distanceTo(junction) in 27.0..29.0)
        assertVisible(actual, actual.path)
        assertEquals(30.0,supplied.approachSpeedLimitKph!!,0.0)
    }
    @Test fun highwayCameraStaysFixedAcrossPhoneMotionAndSpeed() {
        val instruction="Use the right lane to take the I-5 N exit toward Seattle"
        val first=TurnArtworkPolicy.frame(position(speed=0f),geometry(null),instruction)
        val moved=TurnArtworkPolicy.frame(position(47.55,-122.4,speed=30f).copy(bearing=270f),geometry(null),instruction)
        assertSameFraming(first,moved)
    }
    @Test fun highwayViewNeverExtendsShortValidatedDeparture() {
        val supplied=geometry(null).copy(points=listOf(points.first(),junction,offset(70.0,0.0)))
        val frame=TurnArtworkPolicy.frame(position(),supplied,"Take the I-5 N exit on the right")
        assertEquals(supplied.points.last(),frame.path.last())
        assertSame(supplied.points,frame.drawPath)
        assertTrue(frame.path.last().distanceTo(junction)<71)
    }

    @Test fun allCardinalAndDiagonalApproachesEnterFromBottomAndTurnRightOnRight() {
        for (bearing in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)) {
            val radians = Math.toRadians(bearing)
            val before = offset(-sin(radians) * 80, -cos(radians) * 80)
            val exit = offset(cos(radians) * 55, -sin(radians) * 55)
            val road = TurnGeometry(listOf(before, junction, exit), junction, 30.0, "route", "heading-$bearing")
            val fix = position(before.latitude, before.longitude)
            val frame = TurnArtworkPolicy.frame(fix, road)
            assertTrue("bearing=$bearing got=${frame.bearingDegrees}", TurnArtworkPolicy.bearingDifference(bearing, frame.bearingDegrees) < 0.1)
            val anchor = screen(frame, junction); val approach = screen(frame, frame.path.first()); val departure = screen(frame, frame.path.last())
            assertTrue("Approach must enter from bottom for $bearing", approach.second > anchor.second + 15)
            assertEquals("Approach must be vertical for $bearing", anchor.first, approach.first, 0.25)
            assertTrue("Right turn must remain right for $bearing", departure.first > anchor.first + 15)
            assertVisible(frame, frame.path + junction)
        }
    }
    @Test fun leftTurnRemainsLeftWithSouthboundApproach() {
        val road = TurnGeometry(listOf(offset(0.0, 80.0), junction, offset(60.0, 0.0)), junction, 30.0, "route", "left")
        val frame = TurnArtworkPolicy.frame(position(), road)
        assertEquals(180.0, frame.bearingDegrees, 0.1)
        assertTrue(screen(frame, frame.path.last()).first < screen(frame, junction).first)
        assertVisible(frame, frame.path + junction)
    }
    @Test fun headingUsesFinalApproachRatherThanOldRoadDirection() {
        val road = TurnGeometry(listOf(offset(-180.0, -30.0), offset(0.0, -30.0), offset(0.0, -10.0), junction, offset(50.0, 0.0)), junction, 40.0, "route", "curve")
        val frame = TurnArtworkPolicy.frame(position(), road)
        assertTrue(TurnArtworkPolicy.bearingDifference(0.0, frame.bearingDegrees) < 0.1)
    }
    @Test fun curvedFinalApproachUsesBoundedLookbackInsteadOfFirstPoint() {
        val road = TurnGeometry(listOf(offset(-200.0, -20.0), offset(-15.0, -20.0), offset(0.0, -10.0), junction, offset(50.0, 0.0)), junction, 30.0, "route", "curved-final")
        val frame = TurnArtworkPolicy.frame(position(), road)
        assertTrue(frame.bearingDegrees in 25.0..45.0)
        assertVisible(frame, frame.path + junction)
    }
    @Test fun duplicateDenseJunctionVerticesDoNotChooseWrongDirection() {
        val road = TurnGeometry(listOf(offset(0.0, -80.0), offset(0.0, -15.0), junction, junction, junction, offset(50.0, 0.0)), junction, 30.0, "route", "dense")
        assertTrue(TurnArtworkPolicy.validGeometry(road))
        assertTrue(TurnArtworkPolicy.bearingDifference(0.0, TurnArtworkPolicy.frame(position(), road).bearingDegrees) < 0.1)
    }
    @Test fun nonadjacentRepeatedJunctionIsConservativelyRejected() {
        val road = TurnGeometry(listOf(offset(0.0, -80.0), junction, offset(20.0, 0.0), junction, offset(50.0, 0.0)), junction, 30.0, "route", "loop")
        assertFalse(TurnArtworkPolicy.validGeometry(road))
        assertNull(TurnArtworkPolicy.frame(position(), road).geometryKey)
    }
    @Test fun movingFallbackUsesValidCourse() {
        val fix = position(speed = 12f).copy(bearing = 235f)
        assertEquals(235.0, TurnArtworkPolicy.frame(fix, null).bearingDegrees, 0.0)
        assertEquals(235.0, TurnArtworkPolicy.movingCourse(fix)!!, 0.0)
    }
    @Test fun missingOrStationaryCourseDoesNotInventCameraOrientation() {
        for (fix in listOf(position(speed = 12f), position(speed = 0f).copy(bearing = 235f),
            position().copy(bearing = 235f), position(speed = 12f).copy(bearing = Float.NaN),
            position(speed = Float.POSITIVE_INFINITY).copy(bearing = 235f))) {
            assertNull(TurnArtworkPolicy.movingCourse(fix))
            assertEquals(0.0, TurnArtworkPolicy.frame(fix, null).bearingDegrees, 0.0)
        }
    }
    @Test fun cameraBearingCacheDifferenceUsesCircularWrap() {
        val first = TurnArtworkPolicy.Frame(junction, 17.0, "turn", bearingDegrees = 359.0)
        assertTrue(first.compatible(first.copy(bearingDegrees = 1.0)))
        assertFalse(first.compatible(first.copy(bearingDegrees = 10.0)))
        assertFalse(first.compatibleInFlight(first.copy(bearingDegrees = 10.0)))
    }
    @Test fun rotatedCameraPinsActualJunctionAboveBottomCard() {
        val road = TurnGeometry(listOf(offset(-80.0, 0.0), junction, offset(0.0, -60.0)), junction, 30.0, "route", "offset-east")
        val frame = TurnArtworkPolicy.frame(position(), road)
        assertJunctionCentered(frame)
        assertVisible(frame, frame.path + junction)
    }

    @Test fun longCurvingRampFitsEveryDepartureVertexInsteadOfCuttingAtFixedDistance() {
        val departure = listOf(junction, offset(20.0, 140.0), offset(120.0, 300.0),
            offset(300.0, 350.0), offset(420.0, 250.0), offset(430.0, 80.0), offset(360.0, -40.0))
        val road = TurnGeometry(listOf(offset(0.0, -600.0)) + departure, junction, 100.0, "route", "full-ramp")
        val frame = TurnArtworkPolicy.frame(position(), road, "Take the I-5 N ramp toward Seattle")
        assertTrue(frame.path.takeLast(departure.size).zip(departure).all { (a,b) -> a === b })
        assertSame(road.points,frame.drawPath)
        assertVisible(frame,frame.path)
        assertTrue(frame.zoom < 16.0)
        assertJunctionSafelyVisible(frame)
        assertTrue(TurnArtworkPolicy.bearingDifference(0.0,frame.bearingDegrees)<.1)
    }

    @Test fun longManeuverMayZoomOutBelowOldFloorRatherThanDisappear() {
        val departure = listOf(junction, offset(5_000.0,0.0), offset(7_000.0,2_000.0))
        val road = TurnGeometry(listOf(offset(0.0,-150.0)) + departure,junction,100.0,"route","long-real-curve")
        val frame = TurnArtworkPolicy.frame(position(),road)
        assertTrue(frame.zoom < 12.0)
        assertVisible(frame,frame.path)
    }

    @Test fun asymmetricCurvesKeepJunctionCentralAndWholeRampVisibleAtEveryApproachBearing() {
        for(bearing in listOf(0.0,45.0,90.0,135.0,180.0,225.0,270.0,315.0)) {
            val angle=Math.toRadians(bearing)
            fun rotated(east:Double,north:Double)=offset(
                cos(angle)*east+sin(angle)*north, -sin(angle)*east+cos(angle)*north)
            val departure=listOf(junction,rotated(30.0,100.0),rotated(300.0,170.0),
                rotated(550.0,60.0),rotated(600.0,-180.0))
            val road=TurnGeometry(listOf(rotated(0.0,-450.0))+departure,junction,null,"route","pinned-$bearing")
            val frame=TurnArtworkPolicy.frame(position(),road,"Take the I-5 N ramp")
            assertJunctionSafelyVisible(frame)
            assertVisible(frame,frame.path)
            assertSame(departure.last(),frame.path.last())
            assertTrue(TurnArtworkPolicy.bearingDifference(bearing,frame.bearingDegrees)<.1)
        }
    }

    @Test fun ordinaryAsymmetricLeftTurnPinsJunctionRatherThanRouteMidpoint() {
        val road=TurnGeometry(listOf(offset(0.0,-1000.0),junction,offset(-500.0,0.0)),
            junction,40.0,"route","pinned-left")
        val frame=TurnArtworkPolicy.frame(position(),road,"Turn left onto Main Street")
        assertJunctionCentered(frame)
        assertVisible(frame,frame.path)
        assertTrue(screen(frame,frame.path.last()).first<screen(frame,junction).first)
    }

    @Test fun compactHighSpeedTurnCanShowUsefulDetailInsteadOfBeingCappedAtZoomSixteen() {
        val road=TurnGeometry(listOf(offset(0.0,-500.0),junction,offset(40.0,0.0)),
            junction,96.0,"exact-route","compact-high-speed")
        val frame=TurnArtworkPolicy.frame(position(),road,"Turn right onto Main Street")
        assertTrue(frame.zoom > 16.0)
        assertJunctionCentered(frame)
        assertVisible(frame,frame.path)
    }

    @Test fun compactRampStillCentersJunctionWhenEverythingFitsAtDetailScale() {
        val road=TurnGeometry(listOf(offset(0.0,-30.0),junction,offset(20.0,35.0)),
            junction,30.0,"exact-route","compact-ramp")
        val frame=TurnArtworkPolicy.frame(position(),road,"Keep right to take I-5 N")
        assertJunctionCentered(frame)
        assertEquals(17.7,frame.zoom,0.0)
        assertVisible(frame,frame.path)
    }

    @Test fun sixtyMphRampUsesEmptySpaceToShowFullCurveAndTheJoinedRoadCloser() {
        val ramp=listOf(junction,offset(30.0,80.0),offset(100.0,180.0),offset(210.0,260.0),
            offset(260.0,350.0),offset(270.0,470.0))
        val road=TurnGeometry(listOf(offset(0.0,-600.0))+ramp,junction,96.0,"exact-route","ramp-join")
        val frame=TurnArtworkPolicy.frame(position(),road,"Keep right to take I-5 N toward Seattle")
        val centered=TurnArtworkPolicy.frame(position(),road,"Turn right onto Main Street")
        assertTrue("Adaptive zoom=${frame.zoom}, centered=${centered.zoom}",frame.zoom-centered.zoom>.5)
        assertTrue("Camera must look ahead toward joined road",screen(frame,junction).second>ArtworkLayout.ROAD_CENTER_Y)
        assertJunctionSafelyVisible(frame)
        assertVisible(frame,frame.path)
        assertSame(road.points,frame.drawPath)
        assertSame(ramp.last(),frame.path.last())
    }

    @Test fun completeBuiltInRouteStaysNativeBlueOutsideTheSmallCameraWindow() {
        val allPoints=(0..699).map { offset(it*5.0-1500.0,0.0) }
        val route=RoutePlan(allPoints,listOf(RoutePlan.Maneuver(0,allPoints.lastIndex,"Drive east","",1)),60.0,"Fixture")
        val turn=TurnGeometry(route.points.subList(250,361),route.points[300],96.0,"exact-route","full-source",
            sourceRoute=route)
        val frame=TurnArtworkPolicy.frame(position(),turn,"Keep right to take I-5 N")
        assertTrue(TurnArtworkPolicy.validGeometry(turn))
        assertSame(route.points,frame.drawPath)
        assertEquals(700,frame.drawPath.size)
        assertTrue(frame.path.size<turn.points.size)
        assertVisible(frame,frame.path)
    }

    @Test fun explicitRealRampFocusOmitsLongStraightPreludeWithoutChangingGuidanceJunctionOrRoute() {
        val focus=offset(0.0,900.0)
        val ramp=listOf(junction,offset(0.0,450.0),focus,offset(60.0,1000.0),
            offset(220.0,1150.0),offset(350.0,1100.0),offset(400.0,1000.0))
        val turn=TurnGeometry(listOf(offset(0.0,-200.0))+ramp,junction,96.0,"exact-route","long-prelude",
            artworkFocus=focus)
        val frame=TurnArtworkPolicy.frame(position(),turn,"Keep right to take I-5 N")
        assertSame(junction,turn.junction)
        assertSame(focus,frame.junction)
        assertSame(turn.points,frame.drawPath)
        assertTrue(frame.path.first().distanceTo(focus) in 119.0..121.0)
        assertFalse(frame.path.any { it===junction })
        assertSame(turn.points.last(),frame.path.last())
        assertJunctionSafelyVisible(frame)
        assertVisible(frame,frame.path)
    }

    @Test fun unrelatedOrInvalidArtworkFocusCannotReframeTheTurn() {
        for(focus in listOf(offset(1000.0,1000.0),GeoPoint(86.0,-122.0))) {
            val turn=geometry().copy(artworkFocus=focus)
            assertFalse(TurnArtworkPolicy.validGeometry(turn))
            assertNull(TurnArtworkPolicy.frame(position(),turn).geometryKey)
        }
    }

    private fun assertJunctionSafelyVisible(frame:TurnArtworkPolicy.Frame) {
        val actual=screen(frame,checkNotNull(frame.junction))
        assertTrue("junction x=${actual.first}",actual.first in 127.99..384.01)
        assertTrue("junction y=${actual.second}",actual.second in 103.99..272.01)
    }

    private fun assertJunctionCentered(frame:TurnArtworkPolicy.Frame) {
        val actual=screen(frame,checkNotNull(frame.junction))
        assertEquals(256.0,actual.first,.01)
        assertEquals(ArtworkLayout.ROAD_CENTER_Y,actual.second,.01)
    }

    private fun assertSameFraming(expected:TurnArtworkPolicy.Frame,actual:TurnArtworkPolicy.Frame) {
        assertEquals(expected.geometryKey,actual.geometryKey)
        assertEquals(expected.zoom,actual.zoom,0.0)
        assertEquals(expected.bearingDegrees,actual.bearingDegrees,0.0)
        assertEquals(expected.includesPosition,actual.includesPosition)
        assertEquals(expected.markJunction,actual.markJunction)
        assertTrue(expected.center.distanceTo(actual.center)<.001)
        assertEquals(expected.path.size,actual.path.size)
        expected.path.zip(actual.path).forEach{(a,b)->assertTrue(a.distanceTo(b)<.001)}
        assertEquals(expected.drawPath.size,actual.drawPath.size)
        expected.drawPath.zip(actual.drawPath).forEach{(a,b)->assertTrue(a.distanceTo(b)<.001)}
    }

    private fun offset(eastMeters: Double, northMeters: Double): GeoPoint = GeoPoint(
        junction.latitude + northMeters / 111320.0,
        junction.longitude + eastMeters / (111320.0 * cos(Math.toRadians(junction.latitude))))

    private fun screen(frame: TurnArtworkPolicy.Frame, point: GeoPoint): Pair<Double, Double> {
        fun y(latitude: Double): Double { val sine = sin(Math.toRadians(latitude)); return 0.5 - ln((1 + sine) / (1 - sine)) / (4 * Math.PI) }
        var dx = (point.longitude - frame.center.longitude) / 360
        if (dx > 0.5) dx -= 1
        if (dx < -0.5) dx += 1
        val dy = y(point.latitude) - y(frame.center.latitude)
        val angle = Math.toRadians(frame.bearingDegrees); val scale = 512 * 2.0.pow(frame.zoom)
        return (256 + (cos(angle) * dx + sin(angle) * dy) * scale) to
            (256 + (-sin(angle) * dx + cos(angle) * dy) * scale)
    }

    private fun assertVisible(frame: TurnArtworkPolicy.Frame, points: List<GeoPoint>) {
        for (point in points) {
            val (x, y) = screen(frame, point)
            assertTrue("x=$x", x in 22.0..490.0)
            assertTrue("y=$y", y in 16.0..352.0)
        }
    }
}
