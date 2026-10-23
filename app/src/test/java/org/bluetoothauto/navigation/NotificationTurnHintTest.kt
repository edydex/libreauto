// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.junit.Assert.*
import org.junit.Test
import org.bluetoothauto.navigation.NotificationTurnHint.Kind

class NotificationTurnHintTest {
    private fun expect(text: String, kind: Kind, road: String) {
        val hint = NotificationTurnHint.parse(text)!!
        assertEquals(kind,hint.kind); assertEquals(road,hint.road)
    }
    @Test fun namedLeftAndRightTurns() {
        expect("Turn left onto SE 296th St", Kind.LEFT, "SE 296th St")
        expect("Turn right onto Second Street", Kind.RIGHT, "Second Street")
    }
    @Test fun slightSharpAndBearKeepAuthoritativeSide() {
        expect("Turn slightly left onto Pine Ave", Kind.SLIGHT_LEFT,"Pine Ave")
        expect("Turn sharp right onto Pine Ave", Kind.RIGHT,"Pine Ave")
        expect("Bear right onto Pine Ave", Kind.SLIGHT_RIGHT,"Pine Ave")
    }
    @Test fun keepsRequireNamedRoad() {
        expect("Keep left to continue on I-5 N", Kind.KEEP_LEFT,"I-5 N")
        expect("Keep right onto James St", Kind.KEEP_RIGHT,"James St")
        assertNull(NotificationTurnHint.parse("Keep left"))
    }
    @Test fun lanePromptDoesNotReplaceItsActualTurn() {
        expect("Use the right 2 lanes to turn left onto James St",Kind.LEFT,"James St")
        expect("Use the left lane to turn right onto James St",Kind.RIGHT,"James St")
    }
    @Test fun anyLanePrefacePreservesTheExplicitTurnAndRoadRelationship() {
        expect("Use any lane to turn left onto SE 256 St",Kind.LEFT,"SE 256 St")
        expect("Use any lane to turn right onto James Street",Kind.RIGHT,"James Street")
        for (side in listOf("left","right")) {
            val toward=NotificationTurnHint.parse("Use any lane to turn $side toward James Street")!!
            assertEquals(if(side=="left")Kind.LEFT else Kind.RIGHT,toward.kind)
            assertEquals("James Street",toward.road);assertTrue(toward.toward)
            assertFalse(NotificationTurnHint.parse("Use any lane to turn $side onto James Street")!!.toward)
        }
    }
    @Test fun anyLaneDoesNotInventASideOrAcceptAnUnspecifiedAction() {
        val ramp=NotificationTurnHint.parse("Use any lane to take the I-5 N ramp to Test City")!!
        assertEquals(Kind.RAMP,ramp.kind);assertEquals("I-5 N",ramp.road)
        for(text in listOf("Use any lane onto James Street","Use any lane to turn onto James Street",
            "Use any lane to turn left toward Test City","Use any left or right lane to turn left onto James Street"))
            assertNull(text,NotificationTurnHint.parse(text))
    }
    @Test fun genericAndSidedExitsWithExplicitHighwayReferences() {
        expect("Take exit 142A for WA-18 E toward Auburn",Kind.EXIT,"WA-18 E")
        expect("Use the right lane to take exit 142A for WA-18 E",Kind.EXIT_RIGHT,"WA-18 E")
        expect("Exit left onto State Route 18",Kind.EXIT_LEFT,"State Route 18")
    }
    @Test fun rampsRetainLaneSideAndNamedRoute() {
        expect("Take the WA-167 N ramp",Kind.RAMP,"WA-167 N")
        expect("Use the right 2 lanes to take the WA-167 N ramp",Kind.RAMP_RIGHT,"WA-167 N")
        expect("Take the ramp on the left to I-5 N",Kind.RAMP_LEFT,"I-5 N")
    }
    @Test fun mergeAndStraightHaveExplicitRoads() {
        expect("Merge onto I-5 N",Kind.MERGE,"I-5 N")
        expect("Merge left onto I-5 N",Kind.MERGE_LEFT,"I-5 N")
        expect("Continue straight to stay on Pine St",Kind.STRAIGHT,"Pine St")
        expect("Go straight onto James St",Kind.STRAIGHT,"James St")
    }
    @Test fun placeAfterTowardNeverBecomesAuthoritativeRoad() {
        assertNull(NotificationTurnHint.parse("Take exit 11 toward Seattle"))
        assertNull(NotificationTurnHint.parse("Take exit 11 for Seattle"))
        expect("Turn left onto James St toward Seattle",Kind.LEFT,"James St")
    }
    @Test fun bareTurnsHeadingsAndArrivalStayUnsupported() {
        for (text in listOf("Turn left","Continue straight","Head north on Pine St","Arrive at Pine St",
            "Your destination is on the right","At the roundabout take the second exit onto Pine St","Make a U-turn onto Pine St"))
            assertNull(text,NotificationTurnHint.parse(text))
    }
    @Test fun multipleActionsAmbiguousRoadsAndOversizedInputRejected() {
        for (text in listOf("Turn left onto Pine St then turn right","Turn left onto Pine St/James St",
            "Turn left onto Pine St; James St","Turn left onto " + "x".repeat(500),"Turn left onto the left"))
            assertNull(text,NotificationTurnHint.parse(text))
    }
    @Test fun whitespaceAndRoadPunctuationDoNotInventAliases() {
        expect("  TURN   LEFT onto  SE 296th St.  ",Kind.LEFT,"SE 296th St")
        expect("Turn right onto O'Neil Drive",Kind.RIGHT,"O'Neil Drive")
    }
    @Test fun turnOntoHighwayRampRetainsSideAndSearchableRoad() {
        expect("Turn right onto State Highway 18 ramp",Kind.RAMP_RIGHT,"State Highway 18")
        expect("Turn right to take Wash State Rte 18 ramp",Kind.RAMP_RIGHT,"Wash State Rte 18")
        expect("Turn left to take SR-18 E ramp",Kind.RAMP_LEFT,"SR-18 E")
    }
    @Test fun explicitLaneTurnCanNameOneHighwayAndItsStreetAlias() {
        expect("Use the left 2 lanes to turn left onto WA-516 W/SE 272nd St",Kind.LEFT,"WA-516 W/SE 272nd St")
        expect("Use the right lane to turn left onto SE 272nd St/WA-516 W",Kind.LEFT,"SE 272nd St/WA-516 W")
        assertNull(NotificationTurnHint.parse("Turn left onto WA-516 W/I-5 N"))
        assertNull(NotificationTurnHint.parse("Turn left onto WA-516 W/SE 272nd St/James St"))
    }
    @Test fun towardNamedStreetIsAnExplicitlyDifferentRelationship() {
        val hint=NotificationTurnHint.parse("Turn left toward Southeast Falcon Way")!!
        assertEquals(Kind.LEFT,hint.kind);assertEquals("Southeast Falcon Way",hint.road);assertTrue(hint.toward)
        assertFalse(NotificationTurnHint.parse("Turn left onto James St toward Seattle")!!.toward)
        assertTrue(NotificationTurnHint.parse("Use the right lane to turn left towards Pine Street")!!.toward)
    }
    @Test fun towardPoisAndAmbiguousDestinationsDoNotBecomeRoads() {
        for(text in listOf("Turn left toward Seattle","Turn right toward Pine Street Church",
            "Turn left toward Pine Street toward James Street","Take exit 11 toward Seattle"))
            assertNull(text,NotificationTurnHint.parse(text))
    }
    @Test fun highwayExitSignDestinationRemainsSeparateFromItsRoad() {
        val hint=NotificationTurnHint.parse("Use the right lane to take the I-5 N exit toward Test City")!!
        assertEquals(Kind.EXIT_RIGHT,hint.kind)
        assertEquals("I-5 N",hint.road)
        assertEquals("Test City",hint.destinationHint)
        assertFalse(hint.toward)
        assertNull(NotificationTurnHint.parse("Take exit 11 toward Test City"))
    }
    @Test fun destinationProposalsAreBoundedAndOnlyForExplicitHighwayExitsOrRamps() {
        assertNull(NotificationTurnHint.parse("Turn right onto I-5 N toward Test City")!!.destinationHint)
        assertNull(NotificationTurnHint.parse("Exit right onto James St toward Test City")!!.destinationHint)
        assertNull(NotificationTurnHint.parse("Take the I-5 N ramp toward First City/Second City")!!.destinationHint)
        assertNull(NotificationTurnHint.parse("Take the I-5 N ramp toward " + "Long ".repeat(30))!!.destinationHint)
        assertEquals("Test City",NotificationTurnHint.parse("Use the left lane to take the WA-18 W ramp toward Test City")!!.destinationHint)
    }
    @Test fun actualHighwayRampToCityKeepsTheReferenceAndSeparateSignedDestination() {
        val hint=NotificationTurnHint.parse("Use the right lane to take the I-5 N ramp to Seattle")!!
        assertEquals(Kind.RAMP_RIGHT,hint.kind);assertEquals("I-5 N",hint.road)
        assertEquals("Seattle",hint.destinationHint);assertFalse(hint.toward)
        val exit=NotificationTurnHint.parse("Take the WA-18 W exit to Test City")!!
        assertEquals(Kind.EXIT,exit.kind);assertEquals("WA-18 W",exit.road)
        assertEquals("Test City",exit.destinationHint)
    }
    @Test fun explicitOnOntoAndOrdinaryToRoadSemanticsArePreserved() {
        expect("Take the ramp on the right to Pine Street",Kind.RAMP_RIGHT,"Pine Street")
        expect("Take the ramp on the left to I-5 N",Kind.RAMP_LEFT,"I-5 N")
        expect("Take the I-5 N ramp onto Pine Street",Kind.RAMP,"Pine Street")
        expect("Turn right onto Pine Street toward Test City",Kind.RIGHT,"Pine Street")
        assertNull(NotificationTurnHint.parse("Take the ramp on the right to Pine Street")!!.destinationHint)
    }
    @Test fun namedRampToCannotHideAmbiguousHighwayReferencesOrUnsafeDestinationAliases() {
        assertNull(NotificationTurnHint.parse("Take the I-5 N/I-90 E ramp to Test City"))
        assertNull(NotificationTurnHint.parse("Take the I-5 N I-90 E ramp to Test City"))
        val multi=NotificationTurnHint.parse("Take the I-5 N ramp to First City/Second City")!!
        assertEquals("I-5 N",multi.road);assertNull(multi.destinationHint)
        assertNull(NotificationTurnHint.parse("Take the I-5 N ramp to First City toward Second City")!!.destinationHint)
    }
}
