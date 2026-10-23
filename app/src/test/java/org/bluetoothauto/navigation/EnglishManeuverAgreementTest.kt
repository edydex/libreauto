// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.artwork.ManeuverGlyph
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class EnglishManeuverAgreementTest {
    private fun agree(text:String,kind:NotificationTurnHint.Kind,arrow:String,glyph:ManeuverGlyph,road:String) {
        val shared=ManeuverText.parse(text)!!
        val hint=NotificationTurnHint.parse(text)!!
        assertEquals(text,kind.name,shared.kind.name);assertEquals(text,kind,hint.kind)
        assertEquals(text,road,hint.road);assertEquals(text,arrow,NotificationTextParser.arrow(text))
        assertEquals(text,glyph,ManeuverGlyph.fromInstruction(text))
        val notification=NotificationTextParser.parse(listOf("250 ft · $text"),false,true,Locale.US)!!
        assertEquals(text,notification.instruction);assertEquals(76.2,notification.distanceMeters!!,.001)
    }
    private fun neutral(text:String) {
        assertNull(text,ManeuverText.parse(text));assertNull(text,NotificationTurnHint.parse(text))
        assertEquals(text,"NAV",NotificationTextParser.arrow(text))
        assertEquals(text,ManeuverGlyph.UNKNOWN,ManeuverGlyph.fromInstruction(text))
        assertNull(text,NotificationTextParser.parse(listOf(text),false,true,Locale.US))
    }
    private fun prefixes(side:String)=listOf("Use the $side lane to ","Use $side 2 lanes to ",
        "Use the 2 $side lanes to ","Use $side two lanes to ","Use the two $side lanes to ",
        "Use any lane to ","At the traffic lights, use the two $side lanes to ")

    @Test fun everyBoundedLaneOrderingUsesTheFollowingExplicitAction() {
        for(side in listOf("left","right"))for(prefix in prefixes(side)) {
            agree(prefix+"turn left onto MixedCase Pine Street",NotificationTurnHint.Kind.LEFT,"←",ManeuverGlyph.LEFT,"MixedCase Pine Street")
            agree(prefix+"turn right onto MixedCase Pine Street",NotificationTurnHint.Kind.RIGHT,"→",ManeuverGlyph.RIGHT,"MixedCase Pine Street")
            agree(prefix+"bear left onto MixedCase Pine Street",NotificationTurnHint.Kind.SLIGHT_LEFT,"↖",ManeuverGlyph.SLIGHT_LEFT,"MixedCase Pine Street")
            agree(prefix+"keep right onto MixedCase Pine Street",NotificationTurnHint.Kind.KEEP_RIGHT,"↗",ManeuverGlyph.SLIGHT_RIGHT,"MixedCase Pine Street")
            agree(prefix+"merge left onto I-5 N",NotificationTurnHint.Kind.MERGE_LEFT,"↖",ManeuverGlyph.SLIGHT_LEFT,"I-5 N")
        }
    }
    @Test fun everySupportedDigitAndCountWordCanAppearBeforeOrAfterTheSide() {
        for(count in listOf("1","2","3","4","5","6","one","two","three","four","five","six"))
            for(side in listOf("left","right"))for(lanes in listOf("$side $count lanes","$count $side lanes"))
                agree("Use the $lanes to turn right onto Pine Street",NotificationTurnHint.Kind.RIGHT,"→",ManeuverGlyph.RIGHT,"Pine Street")
    }
    @Test fun neutralLaneFormsPreserveExplicitSidesAndNeverSupplyTheirOwn() {
        val forms=listOf("any lane","either lane","both lanes","middle lane","middle lanes",
            "center lane","centre lanes","two middle lanes","middle 2 lanes","2 center lanes","centre two lanes")
        for(lanes in forms)for(the in listOf("","the ")) {
            val prefix="Use $the$lanes to "
            agree(prefix+"turn left onto Pine Street",NotificationTurnHint.Kind.LEFT,"←",ManeuverGlyph.LEFT,"Pine Street")
            agree(prefix+"turn right onto Pine Street",NotificationTurnHint.Kind.RIGHT,"→",ManeuverGlyph.RIGHT,"Pine Street")
            agree(prefix+"merge right onto I-5 N",NotificationTurnHint.Kind.MERGE_RIGHT,"↗",ManeuverGlyph.SLIGHT_RIGHT,"I-5 N")
            val ramp=prefix+"take the I-5 N ramp"
            assertEquals(NotificationTurnHint.Kind.RAMP,NotificationTurnHint.parse(ramp)!!.kind)
            assertEquals("NAV",NotificationTextParser.arrow(ramp));assertEquals(ManeuverGlyph.UNKNOWN,ManeuverGlyph.fromInstruction(ramp))
            neutral(prefix+"see Right Avenue")
        }
    }
    @Test fun sharedGrammarPreservesRoadCaseThroughWhitespaceAndLightPrefaces() {
        val text="  At\tTHE traffic lights,  USE the TWO right lanes to TURN LEFT onto O'Neil Drive  "
        val hint=NotificationTurnHint.parse(text)!!
        assertEquals("O'Neil Drive",hint.road);assertEquals(NotificationTurnHint.Kind.LEFT,hint.kind)
        assertEquals("←",NotificationTextParser.arrow(text));assertEquals(ManeuverGlyph.LEFT,ManeuverGlyph.fromInstruction(text))
        agree("At the light turn right onto Pine Street",NotificationTurnHint.Kind.RIGHT,"→",ManeuverGlyph.RIGHT,"Pine Street")
        agree("At the traffic lights, turn left onto Pine Street",NotificationTurnHint.Kind.LEFT,"←",ManeuverGlyph.LEFT,"Pine Street")
    }
    @Test fun laneSideSuppliesOnlyMissingRampOrMergeDirection() {
        agree("Use two left lanes to take the I-5 N ramp",NotificationTurnHint.Kind.RAMP_LEFT,"↖",ManeuverGlyph.SLIGHT_LEFT,"I-5 N")
        agree("Use the right two lanes to merge onto I-5 N",NotificationTurnHint.Kind.MERGE_RIGHT,"↗",ManeuverGlyph.SLIGHT_RIGHT,"I-5 N")
        agree("Use the right lane to take the I-5 N exit on the left toward Test City",NotificationTurnHint.Kind.EXIT_LEFT,"↖",ManeuverGlyph.SLIGHT_LEFT,"I-5 N")
        for(action in listOf("take the I-5 N ramp","merge onto I-5 N")) {
            val text="Use any lane to $action"
            assertNotNull(NotificationTurnHint.parse(text));assertEquals("NAV",NotificationTextParser.arrow(text))
            assertEquals(ManeuverGlyph.UNKNOWN,ManeuverGlyph.fromInstruction(text))
        }
    }
    @Test fun roadWordsNeverOverrideTheActualManeuverOrSupplyAnExitSide() {
        agree("Turn left onto Exit Road",NotificationTurnHint.Kind.LEFT,"←",ManeuverGlyph.LEFT,"Exit Road")
        agree("Turn right onto Ramp Avenue",NotificationTurnHint.Kind.RIGHT,"→",ManeuverGlyph.RIGHT,"Ramp Avenue")
        agree("Turn left onto Right Lane",NotificationTurnHint.Kind.LEFT,"←",ManeuverGlyph.LEFT,"Right Lane")
        agree("Turn left onto Turn Right Street",NotificationTurnHint.Kind.LEFT,"←",ManeuverGlyph.LEFT,"Turn Right Street")
        agree("Take the I-5 N exit toward Shops on the left",NotificationTurnHint.Kind.EXIT,"NAV",ManeuverGlyph.UNKNOWN,"I-5 N")
        agree("Take the ramp onto On the Right Road",NotificationTurnHint.Kind.RAMP,"NAV",ManeuverGlyph.UNKNOWN,"On the Right Road")
        agree("Take the ramp to The Right Road",NotificationTurnHint.Kind.RAMP,"NAV",ManeuverGlyph.UNKNOWN,"Right Road")
        assertEquals(ManeuverText.Kind.RAMP,ManeuverText.parse("Take the ramp on The Left Road")!!.kind)
        assertEquals("NAV",NotificationTextParser.arrow("Take the ramp on The Left Road"))
    }
    @Test fun explicitHighwayRampTurnsKeepTheirOwnArrowAndNamedRoadMetadata() {
        agree("Use the right lane to turn left onto State Highway 18 ramp.",NotificationTurnHint.Kind.RAMP_LEFT,"←",ManeuverGlyph.LEFT,"State Highway 18")
        agree("Take the ramp on the left to Pine Street",NotificationTurnHint.Kind.RAMP_LEFT,"↖",ManeuverGlyph.SLIGHT_LEFT,"Pine Street")
        val signed=NotificationTurnHint.parse("Use two right lanes to take the I-5 N ramp to Test City")!!
        assertEquals("I-5 N",signed.road);assertEquals("Test City",signed.destinationHint);assertFalse(signed.toward)
    }
    @Test fun ontoAndTowardRemainDifferentRelationshipsAfterTheSamePrefix() {
        for(prefix in prefixes("right")) {
            val onto=NotificationTurnHint.parse(prefix+"turn left onto Pine Street")!!
            val toward=NotificationTurnHint.parse(prefix+"turn left toward Pine Street")!!
            assertEquals(onto.kind,toward.kind);assertEquals(onto.road,toward.road)
            assertFalse(onto.toward);assertTrue(toward.toward)
        }
    }
    @Test fun unsupportedLaneWordsAndCompoundActionsStayNeutralEverywhere() {
        for(text in listOf("Use the left or right lanes to turn left onto Pine Street",
            "Use the left and right lanes to turn left onto Pine Street","Use zero left lanes to turn left onto Pine Street",
            "Use 7 left lanes to turn left onto Pine Street","Use the very left lane to turn left onto Pine Street",
            "Use the two two left lanes to turn left onto Pine Street","Use the left lane",
            "Use any lane to see Right Avenue","Turn left then turn right onto Pine Street",
            "Turn left and turn right onto Pine Street","Turn left; turn right onto Pine Street",
            "Turn left, turn right onto Pine Street","Turn left turn right onto Pine Street",
            "Turn left, right onto Pine Street",
            "At the roundabout, take the third exit, turn right onto Pine Street",
            "Turn left or right onto Pine Street","Turn leftward onto Pine Street","Keep rightly onto Pine Street",
            "Continuation onto Pine Street","At Left Light Road turn left onto Pine Street")) neutral(text)
    }
    @Test fun continuationsAndSlightTurnsHaveTheSameMeaningAcrossAllConsumers() {
        agree("Continue on Pine Street",NotificationTurnHint.Kind.STRAIGHT,"↑",ManeuverGlyph.STRAIGHT,"Pine Street")
        agree("Slight left onto Pine Street",NotificationTurnHint.Kind.SLIGHT_LEFT,"↖",ManeuverGlyph.SLIGHT_LEFT,"Pine Street")
        agree("Take the second right onto Pine Street",NotificationTurnHint.Kind.RIGHT,"→",ManeuverGlyph.RIGHT,"Pine Street")
        assertEquals(ManeuverGlyph.UTURN,ManeuverGlyph.fromInstruction("Make a U-turn"));assertEquals("↶",NotificationTextParser.arrow("Make a U-turn"))
        assertNull(NotificationTurnHint.parse("Make a U-turn onto Pine Street"))
        val roundabout="At the roundabout, take the third exit"
        assertEquals(ManeuverGlyph.ROUNDABOUT,ManeuverGlyph.fromInstruction(roundabout))
        assertEquals("↻",NotificationTextParser.arrow(roundabout));assertNull(NotificationTurnHint.parse(roundabout))
    }
    @Test fun existingLocalizedNotificationAdmissionDoesNotInventAnEnglishDirection() {
        for(text in listOf("Поверните направо","Tournez à droite","Gira a la derecha","Rechts abbiegen")) {
            assertNotNull(NotificationTextParser.parse(listOf(text),false,true,Locale.US))
            assertEquals("NAV",NotificationTextParser.arrow(text));assertEquals(ManeuverGlyph.UNKNOWN,ManeuverGlyph.fromInstruction(text))
        }
    }
}
