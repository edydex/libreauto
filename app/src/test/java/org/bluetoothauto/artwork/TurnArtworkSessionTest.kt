// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.bluetoothauto.navigation.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class TurnArtworkSessionTest {
    private val id = TurnArtworkSession.Identity("Turn right onto Test Road", "Own maps", false, "METRIC")
    private fun frame(key: String = "route:turn-1", offset: Double = 0.0) = TurnArtworkPolicy.Frame(
        GeoPoint(47.0 + offset, -122.0), 17.3, key,
        listOf(GeoPoint(46.9999 + offset, -122.0), GeoPoint(47.0 + offset, -122.0), GeoPoint(47.0 + offset, -121.9999)),
        GeoPoint(47.0 + offset, -122.0), includesPosition = false)

    @Test fun unresolvedTurnDoesNotAcceptCurrentPositionFallbackFrame() {
        val session = TurnArtworkSession()
        val fallback = TurnArtworkPolicy.Frame(GeoPoint(47.0,-122.0),17.0,null)
        assertNull(session.select(id,fallback).frame)
        assertFalse(session.accepts(id,null))
    }
    @Test fun confirmedGeometryCanUpgradeAnUnchangedInstruction() {
        val session=TurnArtworkSession();session.select(id,null)
        val confirmed=frame()
        assertSame(confirmed,session.select(id,confirmed).frame)
        assertTrue(session.accepts(id,confirmed.geometryKey))
    }
    @Test fun movingOrRewrappedSameTurnCannotMoveLockedMap() {
        val session=TurnArtworkSession();val first=frame();session.select(id,first)
        val movedAndRebuilt=frame(offset=0.01).copy(zoom=15.0,bearingDegrees=180.0)
        val result=session.select(id,movedAndRebuilt)
        assertFalse(result.invalidated);assertSame(first,result.frame)
    }
    @Test fun MissingGpsOrTemporaryGeometryLossKeepsFixedJunctionNotPhoneLocation() {
        val session=TurnArtworkSession();val first=frame();session.select(id,first)
        repeat(100){assertSame(first,session.select(id,null).frame)}
        assertTrue(session.accepts(id,null))
    }
    @Test fun newGeometryIdentityResetsEvenWhenInstructionTextRepeats() {
        val session=TurnArtworkSession();session.select(id,frame())
        val second=frame("route:turn-2",0.001)
        val result=session.select(id,second)
        assertTrue(result.invalidated);assertSame(second,result.frame)
    }
    @Test fun repeatedInstructionWithNewTurnButNoUsableFixClearsOldMap() {
        val session=TurnArtworkSession();session.select(id,frame())
        val result=session.select(id,null,"route:turn-2")
        assertTrue(result.invalidated);assertNull(result.frame)
        assertFalse(session.accepts(id,"route:turn-2"))
    }
    @Test fun newInstructionSourceDemoOrUnitsCannotRetainPreviousMap() {
        for(next in listOf(id.copy(instruction="Turn left"),id.copy(source="Google Maps"),id.copy(demo=true),id.copy(units="IMPERIAL"))){
            val session=TurnArtworkSession();session.select(id,frame())
            val result=session.select(next,null)
            assertTrue(result.invalidated);assertNull(result.frame)
        }
    }
    @Test fun clearingGuidancePreventsIdenticalLaterTurnFromReusingOldMap() {
        val session=TurnArtworkSession();session.select(id,frame())
        assertNull(session.select(null,null).frame)
        assertNull(session.select(id,null).frame)
    }
    @Test fun explicitSettingsResetAllowsFreshGeometryForSameTurn() {
        val session=TurnArtworkSession();val first=frame();session.select(id,first);session.reset()
        val replacement=frame(offset=0.001)
        assertSame(replacement,session.select(id,replacement).frame)
        assertNotSame(first,session.select(id,replacement).frame)
    }
    @Test fun longSessionHasNoTimeOrDistanceExpiryForAnAcceptedTurn() {
        val session=TurnArtworkSession();val first=frame();session.select(id,first)
        repeat(1000){assertSame(first,session.select(id,frame(offset=it*0.001)).frame)}
    }
}
