// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Guidance
import org.junit.Assert.*
import org.junit.Test

class GuidanceChangePolicyTest {
    private val turn = Guidance("Turn right onto Main Street", 120.0, source="Maps")
    @Test fun distanceCountdownKeepsTheTurn() {
        assertFalse(GuidanceChangePolicy.startsNewTurn(turn, turn.copy(distanceMeters=80.0)))
        assertFalse(GuidanceChangePolicy.startsNewTurn(turn, turn.copy(distanceMeters=145.0)))
    }
    @Test fun repeatedTextAfterTurnOrRerouteLosesOldPicture() {
        assertTrue(GuidanceChangePolicy.startsNewTurn(turn.copy(distanceMeters=20.0),turn.copy(distanceMeters=200.0)))
        assertTrue(GuidanceChangePolicy.startsNewTurn(turn,turn.copy(distanceMeters=900.0)))
    }
    @Test fun newInstructionSourceAndClearStartANewPreview() {
        assertTrue(GuidanceChangePolicy.startsNewTurn(turn,turn.copy(instruction="Turn left onto Main Street")))
        assertTrue(GuidanceChangePolicy.startsNewTurn(turn,turn.copy(source="Open maps")))
        assertTrue(GuidanceChangePolicy.startsNewTurn(turn,null))
        assertTrue(GuidanceChangePolicy.startsNewTurn(null,turn))
        assertFalse(GuidanceChangePolicy.startsNewTurn(null,null))
    }
    @Test fun missingOrUnusableDistanceDoesNotInventANewJunction() {
        for(value in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0))
            assertFalse(GuidanceChangePolicy.startsNewTurn(turn,turn.copy(distanceMeters=value)))
    }
    @Test fun repeatedInstructionOnConfirmedContinuingRouteKeepsFutureMaps() {
        val before=turn.copy(distanceMeters=20.0,source="Own maps")
        val after=before.copy(distanceMeters=250.0)
        // The displayed junction still resets; only the separately keyed future maps survive.
        assertTrue(GuidanceChangePolicy.startsNewTurn(before,after))
        assertTrue(GuidanceChangePolicy.shouldKeepPrepared(before,after,confirmedRouteContinues=true))
    }
    @Test fun identicalGoogleWordingWithoutRouteProofClearsFutureMaps() {
        val before=turn.copy(distanceMeters=20.0,source="Google Maps")
        val after=before.copy(distanceMeters=250.0)
        assertTrue(GuidanceChangePolicy.startsNewTurn(before,after))
        assertFalse(GuidanceChangePolicy.shouldKeepPrepared(before,after,confirmedRouteContinues=false))
    }
    @Test fun identicalOwnWordingAfterUnconfirmedRerouteClearsFutureMaps() {
        val before=turn.copy(distanceMeters=20.0,source="Own maps")
        assertFalse(GuidanceChangePolicy.shouldKeepPrepared(before,before.copy(distanceMeters=800.0),confirmedRouteContinues=false))
    }
    @Test fun changedInstructionRetainsTheExistingKeepBehavior() {
        assertTrue(GuidanceChangePolicy.shouldKeepPrepared(turn,turn.copy(instruction="Turn left onto Second Street"),confirmedRouteContinues=false))
    }
    @Test fun sourceChangeOverridesConfirmedRouteFlag() {
        assertFalse(GuidanceChangePolicy.shouldKeepPrepared(turn,turn.copy(source="Own maps"),confirmedRouteContinues=true))
    }
    @Test fun demoModeChangeOverridesConfirmedRouteFlagInBothDirections() {
        assertFalse(GuidanceChangePolicy.shouldKeepPrepared(turn,turn.copy(demo=true),confirmedRouteContinues=true))
        assertFalse(GuidanceChangePolicy.shouldKeepPrepared(turn.copy(demo=true),turn,confirmedRouteContinues=true))
    }
    @Test fun missingGuidanceNeverRetainsFutureMaps() {
        assertFalse(GuidanceChangePolicy.shouldKeepPrepared(turn,null,confirmedRouteContinues=true))
        assertFalse(GuidanceChangePolicy.shouldKeepPrepared(null,turn,confirmedRouteContinues=true))
        assertFalse(GuidanceChangePolicy.shouldKeepPrepared(null,null,confirmedRouteContinues=true))
    }
    @Test fun transientGuidanceNeverRetainsFutureMapsEvenWithRouteFlag() {
        for(text in listOf("Recalculating route","Calculating route","Off route · check directions on your phone","Off-route","Waiting for an accurate location")) {
            val transient=turn.copy(instruction=text)
            assertFalse(text,GuidanceChangePolicy.shouldKeepPrepared(turn,transient,confirmedRouteContinues=true))
            assertFalse(text,GuidanceChangePolicy.shouldKeepPrepared(transient,turn,confirmedRouteContinues=true))
        }
    }
}
