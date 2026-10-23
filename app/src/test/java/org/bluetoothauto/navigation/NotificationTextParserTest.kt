// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NotificationTextParserTest {
    @Test fun explicitLaneExitKeepsItsDirectionInSongTitle() {
        assertEquals("↗", NotificationTextParser.arrow("Use the right lane to take the I-5 N exit toward Seattle"))
        assertEquals("↖", NotificationTextParser.arrow("Use the left lane to take the I-5 N exit toward Seattle"))
        assertEquals("NAV", NotificationTextParser.arrow("Take the I-5 N exit toward Seattle"))
        assertEquals("→", NotificationTextParser.arrow("Use the left lane to turn right onto Main Street"))
    }

    private fun parse(vararg fields: String, category: Boolean = true, ongoing: Boolean = true) =
        NotificationTextParser.parse(fields.toList(), category, ongoing, Locale.US)

    @Test fun distancePrefixAndInstructionAreSeparated() {
        val parsed = parse("200 m - Turn right onto Main Street", "12 min · 4.2 km · 10:45 AM")!!
        assertEquals("Turn right onto Main Street", parsed.instruction)
        assertEquals(200.0, parsed.distanceMeters!!, 0.001)
        assertEquals("200 m", parsed.distanceLabel)
        assertFalse(parsed.status)
    }
    @Test fun distanceOnlyTitleCanUseStreetTextWithoutInventingTurn() {
        val parsed = parse("500 ft", "Left Bank Avenue")!!
        assertEquals("Left Bank Avenue", parsed.instruction)
        assertEquals(152.4, parsed.distanceMeters!!, 0.001)
        assertEquals("NAV", NotificationTextParser.arrow(parsed.instruction))
    }
    @Test fun explicitStatusDropsOldDistanceAndDirections() {
        for (status in listOf("Finding GPS…", "Rerouting", "GPS signal lost", "Recalculating route...")) {
            val parsed = parse(status, "200 m - Turn right")!!
            assertTrue(parsed.status); assertNull(parsed.distanceMeters); assertEquals("", parsed.distanceLabel)
            assertEquals("NAV", NotificationTextParser.arrow(parsed.instruction))
        }
    }
    @Test fun unrelatedOngoingAndOrdinaryNotificationsAreRejected() {
        assertNull(parse("Try a restaurant 200 m away", category = false))
        assertNull(parse("200 m - Turn right", category = false, ongoing = false))
        assertNull(parse("A new review", "Your photo has 20 views", category = false))
        assertNull(parse("Take a photo 200 m away", category = false))
        assertNull(parse("Continue exploring", "20 km nearby", category = false))
    }
    @Test fun ongoingKnownTurnIsAcceptedWithoutNavigationCategory() {
        assertEquals("Turn left", parse("100 m - Turn left", category = false)!!.instruction)
    }
    @Test fun tripDistanceNeverBecomesDistanceToTurn() {
        val parsed = parse("Turn right", "12 min · 4.2 km · 10:45 AM")!!
        assertNull(parsed.distanceMeters)
    }
    @Test fun unicodeGroupedAndDecimalDistancesRespectLocale() {
        fun meters(value: String, locale: Locale) = NotificationTextParser.parseDistance(value, locale)!!.meters
        assertEquals(1200.0, meters("1,200 m", Locale.US), 0.001)
        assertEquals(1200.0, meters("1.200 m", Locale.GERMANY), 0.001)
        assertEquals(1200.0, meters("1,2 km", Locale.FRANCE), 0.001)
        assertEquals(1200.0, meters("1.2 km", Locale.US), 0.001)
        assertEquals(1200.0, meters("1\u202f200 m", Locale.FRANCE), 0.001)
        assertEquals(1200.0, meters("١٫٢ km", Locale("ar")), 0.001)
        assertEquals(1609.344, meters("1 mi", Locale.US), 0.001)
        assertEquals(91.44, meters("100 yd", Locale.US), 0.001)
    }
    @Test fun malformedNegativeAndHugeDistancesAreRejected() {
        assertNull(NotificationTextParser.parseDistance("-100 m", Locale.US))
        assertNull(NotificationTextParser.parseDistance("100 min", Locale.US))
        assertNull(NotificationTextParser.parseDistance("99999999999 km", Locale.US))
        assertNull(NotificationTextParser.parseDistance("x100 m", Locale.US))
        assertNull(NotificationTextParser.parseDistance(".1 km", Locale.US))
        assertNull(NotificationTextParser.parseDistance("1,2,3 km", Locale.US))
        assertNull(NotificationTextParser.parseDistance("1,23.45 km", Locale.US))
    }
    @Test fun unrecognizedNavigationStatusIsNeutral() {
        val parsed = parse("Navigation paused")!!
        assertTrue(parsed.status); assertNull(parsed.distanceMeters)
    }
    @Test fun onlyAnchoredKnownCommandsProduceArrows() {
        assertEquals("→", NotificationTextParser.arrow("Turn right onto Left Bank Road"))
        assertEquals("←", NotificationTextParser.arrow("Turn left onto Right Avenue"))
        assertEquals("↗", NotificationTextParser.arrow("Bear right"))
        assertEquals("↶", NotificationTextParser.arrow("Make a U-turn"))
        assertEquals("NAV", NotificationTextParser.arrow("Right Bank Avenue"))
        assertEquals("↑", NotificationTextParser.arrow("Continue on Main Street"))
        assertEquals("→", NotificationTextParser.arrow("Turn right onto Slight Avenue"))
    }
    @Test fun untrustedStringsAreBoundedAndControlsRemoved() {
        val parsed = parse("Turn right " + "x".repeat(4000))!!
        assertTrue(parsed.instruction.length <= 240)
        assertEquals("Turn right", NotificationTextParser.clean("Turn\nright\u202e"))
    }
    @Test fun ongoingLanePrefixedCommandIsAcceptedAndPreservedVerbatim() {
        val instruction="Use the left 2 lanes to turn left onto WA-516 W/SE 272nd St"
        val result=parse("500 ft - $instruction",category=false)!!
        assertEquals(instruction,result.instruction);assertEquals("←",NotificationTextParser.arrow(result.instruction))
    }
    @Test fun actualAnyLaneNotificationKeepsItsDistanceInstructionAndLeftArrow() {
        val instruction="Use any lane to turn left onto SE 256 St"
        val result=parse("3.3 mi · $instruction",category=false)!!
        assertEquals(instruction,result.instruction);assertFalse(result.status)
        assertEquals(3.3*1609.344,result.distanceMeters!!,.001)
        assertEquals("←",NotificationTextParser.arrow(result.instruction))
    }
    @Test fun anyLaneTowardCommandsKeepTheirExplicitSideWithoutInventingMissingActions() {
        val instruction="Use any lane to turn right toward James Street"
        assertEquals(instruction,parse(instruction,category=false)!!.instruction)
        assertEquals("→",NotificationTextParser.arrow(instruction))
        for(text in listOf("Use any lane onto James Street","Use any lane to turn onto James Street",
            "Use any left or right lane to turn left")) {
            assertNull(parse(text,category=false));assertEquals("NAV",NotificationTextParser.arrow(text))
        }
    }
    @Test fun ambiguousLaneSelectionDoesNotBecomeACommandOrDirection() {
        val instruction="Use the left or right lanes to turn left"
        assertNull(parse(instruction,category=false));assertEquals("NAV",NotificationTextParser.arrow(instruction))
    }
    @Test fun liveCriticalDistanceOverridesAnOlderTitleDistance() {
        val old = parse("1 mi - Turn left toward Main Street")!!
        val current = NotificationTextParser.withCriticalDistance(old, "250 ft", Locale.US)
        assertEquals(old.instruction, current.instruction)
        assertEquals(76.2, current.distanceMeters!!, .001)
        assertEquals("250 ft", current.distanceLabel)
    }
    @Test fun liveCriticalDistanceCompletesAnInstructionWithoutDistance() {
        val old = parse("Turn left toward Main Street")!!
        assertNull(old.distanceMeters)
        assertEquals(15.24, NotificationTextParser.withCriticalDistance(old, "50 ft", Locale.US).distanceMeters!!, .001)
    }
    @Test fun liveCriticalDistanceNeverAttachesDistanceToAStatus() {
        val status = parse("Rerouting")!!
        assertEquals(status, NotificationTextParser.withCriticalDistance(status, "250 ft", Locale.US))
    }
    @Test fun liveCriticalDistanceRejectsSummaryOrEmbeddedText() {
        val old = parse("1 mi - Turn left")!!
        for (value in listOf("ETA 250 ft", "250 ft - turn left", "12 min", "-50 ft", "99".repeat(40)))
            assertEquals(old, NotificationTextParser.withCriticalDistance(old, value, Locale.US))
    }
}
