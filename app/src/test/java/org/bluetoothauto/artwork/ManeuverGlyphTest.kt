// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverGlyphTest {
    @Test fun explicitLeft() { assertEquals(ManeuverGlyph.LEFT, ManeuverGlyph.fromInstruction("Turn left onto Pine Street")) }
    @Test fun explicitRight() { assertEquals(ManeuverGlyph.RIGHT, ManeuverGlyph.fromInstruction("Turn right onto First Avenue")) }
    @Test fun uTurnIsNotGenericTurn() { assertEquals(ManeuverGlyph.UTURN, ManeuverGlyph.fromInstruction("Make a U-turn")) }
    @Test fun straightIsExplicit() { assertEquals(ManeuverGlyph.STRAIGHT, ManeuverGlyph.fromInstruction("Continue on Main Street")) }
    @Test fun roundaboutDoesNotInventExit() { assertEquals(ManeuverGlyph.ROUNDABOUT, ManeuverGlyph.fromInstruction("At the roundabout, take the third exit")) }
    @Test fun arriveFlag() { assertEquals(ManeuverGlyph.ARRIVE, ManeuverGlyph.fromInstruction("Arrive at your destination")) }
    @Test fun streetNameRightIsNotManeuver() { assertEquals(ManeuverGlyph.UNKNOWN, ManeuverGlyph.fromInstruction("Brighton Road")) }
    @Test fun untranslatedInstructionDoesNotInventTurn() { assertEquals(ManeuverGlyph.UNKNOWN, ManeuverGlyph.fromInstruction("Поверните направо")) }
    @Test fun ambiguousInstructionDoesNotInventTurn() { assertEquals(ManeuverGlyph.UNKNOWN, ManeuverGlyph.fromInstruction("In 500m choose the correct lane")) }
    @Test fun slightLeftKeepsItsShallowAngle() { assertEquals(ManeuverGlyph.SLIGHT_LEFT, ManeuverGlyph.fromInstruction("Slight left onto Pine Street")) }
    @Test fun slightRightKeepsItsShallowAngle() { assertEquals(ManeuverGlyph.SLIGHT_RIGHT, ManeuverGlyph.fromInstruction("Turn slightly right onto Pine Street")) }
    @Test fun keepLeftIsNotNinetyDegreeTurn() { assertEquals(ManeuverGlyph.SLIGHT_LEFT, ManeuverGlyph.fromInstruction("Keep left at the fork")) }
    @Test fun keepRightIsNotNinetyDegreeTurn() { assertEquals(ManeuverGlyph.SLIGHT_RIGHT, ManeuverGlyph.fromInstruction("Keep right at the fork")) }
    @Test fun demoArrivalHasArrivalFlag() { assertEquals(ManeuverGlyph.ARRIVE, ManeuverGlyph.fromInstruction("You have arrived")) }
    @Test fun rightInStreetNameDoesNotBecomeRightTurn() { assertEquals(ManeuverGlyph.LEFT, ManeuverGlyph.fromInstruction("Turn left onto Wright Avenue")) }
    @Test fun liveLanePrefaceStillDrawsItsActualLeftTurn() {
        assertEquals(ManeuverGlyph.LEFT,ManeuverGlyph.fromInstruction("Use the left 2 lanes to turn left onto WA-516 W/SE 272nd St"))
    }
    @Test fun laneChoiceDoesNotOverrideTheFollowingTurn() {
        assertEquals(ManeuverGlyph.RIGHT,ManeuverGlyph.fromInstruction("Use the left lane to turn right onto Pine St"))
        assertEquals(ManeuverGlyph.LEFT,ManeuverGlyph.fromInstruction("Use the 2 right lanes to turn left onto Pine St"))
    }
    @Test fun lanePrefixedKeepAndExplicitMergeRetainShallowArrows() {
        assertEquals(ManeuverGlyph.SLIGHT_LEFT,ManeuverGlyph.fromInstruction("Use the right lane to merge left onto I-5 N"))
        assertEquals(ManeuverGlyph.SLIGHT_RIGHT,ManeuverGlyph.fromInstruction("Use the left two lanes to keep right"))
        assertEquals(ManeuverGlyph.SLIGHT_LEFT,ManeuverGlyph.fromInstruction("Use the left lane to merge onto I-5 N"))
    }
    @Test fun lanePrefaceWithUnknownActionOrAmbiguousChoiceStaysNeutral() {
        for(text in listOf("Use the left lane","Use the left lane to see Right Avenue",
            "Use the left or right lanes to turn left","Use any lane to merge onto I-5 N"))
            assertEquals(text,ManeuverGlyph.UNKNOWN,ManeuverGlyph.fromInstruction(text))
    }
    @Test fun liveRightLaneExitDrawsShallowRightArrow() {
        assertEquals(ManeuverGlyph.SLIGHT_RIGHT,ManeuverGlyph.fromInstruction(
            "Use the right lane to take the I-5 N exit toward Seattle"))
    }
    @Test fun laneQualifiedRampUsesItsKnownSide() {
        assertEquals(ManeuverGlyph.SLIGHT_LEFT,ManeuverGlyph.fromInstruction(
            "Use the left lane to take the I-5 N ramp"))
    }
    @Test fun exitWithoutKnownSideStaysNeutral() {
        for(text in listOf("Take the I-5 N exit toward Seattle","Take the I-5 N ramp"))
            assertEquals(text,ManeuverGlyph.UNKNOWN,ManeuverGlyph.fromInstruction(text))
    }
    @Test fun explicitTurnStillOverridesOppositeLaneEvenForExit() {
        assertEquals(ManeuverGlyph.LEFT,ManeuverGlyph.fromInstruction(
            "Use the right lane to turn left onto the I-5 N exit toward Seattle"))
    }
    @Test fun explicitExitSideOverridesLaneSide() {
        assertEquals(ManeuverGlyph.SLIGHT_LEFT,ManeuverGlyph.fromInstruction(
            "Use the right lane to take the I-5 N exit on the left toward Seattle"))
    }
}
