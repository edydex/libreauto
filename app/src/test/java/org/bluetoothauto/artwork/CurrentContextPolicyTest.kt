// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.bluetoothauto.model.Position
import org.bluetoothauto.navigation.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class CurrentContextPolicyTest {
    private fun fix(north:Double=0.0, at:Long=1_000, bearing:Float?=null, speed:Float?=null)=
        Position(47.6062+north/111_195.08,-122.3321,bearing,5f,at,speedMps=speed)
    private fun frame()=CurrentContextPolicy.frame(fix(),1_000)!!

    @Test fun actualAreaFrameHasNoRoadOrJunctionClaim() {
        val position=fix();val frame=frame()
        assertTrue(frame.currentContext);assertTrue(frame.includesPosition)
        assertNull(frame.geometryKey);assertNull(frame.junction)
        assertTrue(frame.path.isEmpty());assertTrue(frame.drawPath.isEmpty());assertFalse(frame.markJunction)
        assertEquals(position.latitude,frame.center.latitude,0.0)
        assertEquals(position.longitude,frame.center.longitude,0.0)
    }
    @Test fun areaRemainsNorthUpWhileActualMovingPuckCourseChanges() {
        val north=CurrentContextPolicy.frame(fix(bearing=0f,speed=10f),1_000)!!
        val east=CurrentContextPolicy.frame(fix(bearing=90f,speed=10f),1_000)!!
        assertEquals(0.0,north.bearingDegrees,0.0);assertEquals(0.0,east.bearingDegrees,0.0)
        assertEquals(north.zoom,east.zoom,0.0)
        assertNotEquals(CurrentContextPolicy.puckKey(fix(bearing=0f,speed=10f)),
            CurrentContextPolicy.puckKey(fix(bearing=90f,speed=10f)))
    }
    @Test fun unchangedFreshAreaReusesSnapshotUntilFifteenSeconds() {
        assertTrue(CurrentContextPolicy.canReuse(frame(),1_000,fix(at=15_999),15_999))
        assertFalse(CurrentContextPolicy.canReuse(frame(),1_000,fix(at=16_000),16_000))
    }
    @Test fun spatialCacheHasASeparateHundredMetreBound() {
        assertTrue(CurrentContextPolicy.canReuse(frame(),1_000,fix(north=99.0,at=2_000),2_000))
        assertFalse(CurrentContextPolicy.canReuse(frame(),1_000,fix(north=101.0,at=2_000),2_000))
    }
    @Test fun priorAreaCanRemainVisibleWhileReplacementIsLoading() {
        assertFalse(CurrentContextPolicy.canReuse(frame(),1_000,fix(at=30_000),30_000))
        assertTrue(CurrentContextPolicy.canDisplay(frame(),fix(at=30_000),30_000))
        assertFalse(CurrentContextPolicy.canDisplay(frame(),fix(north=200.0,at=30_000),30_000))
    }
    @Test fun staleFutureInaccurateAndInvalidFixesCannotCreateContext() {
        for(position in listOf(fix(),fix(at=32_001),fix(at=32_000).copy(accuracy=101f),
            fix(at=32_000).copy(latitude=Double.NaN),fix(at=32_000).copy(longitude=181.0)))
            assertNull(CurrentContextPolicy.frame(position,32_000))
    }
    @Test fun positionTimestampAloneDoesNotRecomposeSamePuck() {
        assertEquals(CurrentContextPolicy.puckKey(fix()),CurrentContextPolicy.puckKey(fix(at=2_000)))
        assertEquals(CurrentContextPolicy.puckKey(fix()),CurrentContextPolicy.puckKey(fix(north=.01,at=2_000)))
        assertNotEquals(CurrentContextPolicy.puckKey(fix()),CurrentContextPolicy.puckKey(fix(north=5.0,at=2_000)))
    }
    @Test fun stationaryOrInvalidCourseDoesNotInventHeadingArrow() {
        assertNull(CurrentContextPolicy.puckKey(fix(bearing=90f,speed=0f))!!.courseStep)
        assertNull(CurrentContextPolicy.puckKey(fix(bearing=Float.NaN,speed=10f))!!.courseStep)
        assertEquals(18,CurrentContextPolicy.puckKey(fix(bearing=90f,speed=10f))!!.courseStep)
    }
    @Test fun confirmedRouteFrameIsNeverReusedAsNeutralArea() {
        val context=frame()
        val confirmed=context.copy(geometryKey="actual-turn",junction=GeoPoint(47.6062,-122.3321),
            path=listOf(GeoPoint(47.6061,-122.3321),GeoPoint(47.6062,-122.3321)),includesPosition=false)
        assertFalse(confirmed.currentContext)
        assertFalse(CurrentContextPolicy.canDisplay(confirmed,fix(),1_000))
    }
    @Test fun currentContextCannotBecomeALockedConfirmedTurn() {
        val session=TurnArtworkSession()
        val identity=TurnArtworkSession.Identity("Turn left toward Test Road","Google Maps",false,"AUTO")
        assertNull(session.select(identity,frame()).frame)
        assertFalse(session.accepts(identity,null))
        val confirmed=TurnArtworkPolicy.Frame(GeoPoint(47.6062,-122.3321),17.0,"actual-turn",
            listOf(GeoPoint(47.6061,-122.3321),GeoPoint(47.6062,-122.3321),GeoPoint(47.6062,-122.3323)),
            GeoPoint(47.6062,-122.3321),includesPosition=false)
        assertSame(confirmed,session.select(identity,confirmed).frame)
        assertSame(confirmed,session.select(identity,frame()).frame)
    }
}
