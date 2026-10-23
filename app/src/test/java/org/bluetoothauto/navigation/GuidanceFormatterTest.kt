// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Guidance
import org.junit.Assert.*
import org.junit.Test

class GuidanceFormatterTest {
    private fun guidance(instruction: String, meters: Double? = null, label: String = "") =
        Guidance(instruction, meters, label, icon = null, receivedAt = 0)
    @Test fun noGuidancePreservesSongTitle() {
        assertEquals("Song", GuidanceFormatter.title(null, "Song", "AUTO"))
    }
    @Test fun unavailableIconAndUnknownDirectionUseNavPrefix() {
        assertEquals("NAV 300 m · Song", GuidanceFormatter.title(guidance("Main Street", 300.0, "300 m"), "Song", "AUTO"))
    }
    @Test fun knownTurnPrefixesSongWithoutMutatingSong() {
        assertEquals("-> 300 m · Song", GuidanceFormatter.title(guidance("Turn right", 300.0, "300 m"), "Song", "AUTO"))
    }
    @Test fun olderStereoPrefixesUseAsciiForEveryKnownManeuver() {
        val examples = mapOf("Turn right" to "->", "Turn left" to "<-", "Continue straight" to "^",
            "Bear right" to "/->", "Bear left" to "<-\\", "Make a U-turn" to "UTURN", "At the roundabout" to "ROUND")
        for ((instruction, prefix) in examples) {
            val title = GuidanceFormatter.title(guidance(instruction), "Song", "AUTO")
            assertEquals("$prefix · Song", title)
            assertTrue(title.substringBefore(" · ").all { it.code in 32..126 })
        }
    }
    @Test fun asciiManeuverPrefixPreservesNonAsciiSongTitle() {
        assertEquals("-> 300 m · Моя песня 🎵", GuidanceFormatter.title(guidance("Turn right", 300.0, "300 m"), "Моя песня 🎵", "AUTO"))
    }
    @Test fun userUnitsConvertDistanceAndAutoPreservesNotificationUnits() {
        assertEquals("500 ft", GuidanceFormatter.formatDistance(152.4, "500 ft", "AUTO"))
        assertEquals("152 m", GuidanceFormatter.formatDistance(152.4, "500 ft", "METRIC"))
        assertEquals("500 ft", GuidanceFormatter.formatDistance(152.4, "152 m", "IMPERIAL"))
        assertEquals("1.0 mi", GuidanceFormatter.formatDistance(1609.344, "", "IMPERIAL"))
        assertEquals("1.2 km", GuidanceFormatter.formatDistance(1200.0, "", "METRIC"))
    }
    @Test fun routeStatusDoesNotReuseOldDistanceOrInventArrow() {
        assertEquals("NAV · Song", GuidanceFormatter.title(guidance("Finding GPS"), "Song", "AUTO"))
    }
    @Test fun invalidAndAbsentDistancesDoNotRenderFalseZero() {
        for (meters in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0))
            assertEquals("", GuidanceFormatter.formatDistance(meters, "", "METRIC"))
        assertEquals("0 m", GuidanceFormatter.formatDistance(0.0, "", "METRIC"))
    }
    @Test fun metadataStringsHaveBoundedLengthAndNoControlCharacters() {
        val title = GuidanceFormatter.title(guidance("Turn left", 1200.0), "x".repeat(1000) + "\n", "METRIC")
        assertTrue(title.length <= 180); assertFalse(title.contains('\n'))
    }
    @Test fun liveLanePrefaceUsesActualLeftArrowInStereoTitle() {
        assertEquals("<- 500 ft · Song",GuidanceFormatter.title(guidance(
            "Use the left 2 lanes to turn left onto WA-516 W/SE 272nd St",152.4,"500 ft"),"Song","AUTO"))
    }
    @Test fun lanePrefaceDoesNotConfuseLaneSideWithTurnOrMergeSide() {
        assertEquals("-> · Song",GuidanceFormatter.title(guidance("Use the left lane to turn right onto Pine St"),"Song","AUTO"))
        assertEquals("<-\\ · Song",GuidanceFormatter.title(guidance("Use the right lane to merge left onto I-5 N"),"Song","AUTO"))
        assertEquals("<-\\ · Song",GuidanceFormatter.title(guidance("Use the left lane to merge onto I-5 N"),"Song","AUTO"))
        assertEquals("/-> · Song",GuidanceFormatter.title(guidance("Use the right lane to merge onto I-5 N"),"Song","AUTO"))
        assertEquals("NAV · Song",GuidanceFormatter.title(guidance("Use any lane to merge onto I-5 N"),"Song","AUTO"))
    }
}
