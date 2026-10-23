// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Guidance
import org.junit.Assert.*
import org.junit.Test

class HintPlacementTest {
    private val guidance=Guidance("Turn right onto Main Street",120.0,"120 m",receivedAt=0)

    @Test fun absentOrInvalidPreferenceRetainsBoth() {
        for(value in listOf(null,"","title_only","unknown"))
            assertEquals(HintPlacement.BOTH,HintPlacement.fromPreference(value))
    }
    @Test fun eachStablePreferenceValueRoundTrips() {
        for(mode in HintPlacement.entries)assertEquals(mode,HintPlacement.fromPreference(mode.name))
    }
    @Test fun titleOnlyAddsTurnAndKeepsMusicArtwork() {
        val mode=HintPlacement.TITLE_ONLY
        assertEquals("-> 120 m · Evening drive",GuidanceFormatter.title(mode.titleGuidance(guidance),"Evening drive","AUTO"))
        assertFalse(mode.navigationArtwork(guidance))
        assertFalse(mode.artworkLocation(true))
    }
    @Test fun artworkOnlyPreservesOriginalSongTitle() {
        val mode=HintPlacement.ARTWORK_ONLY
        assertEquals("Evening drive",GuidanceFormatter.title(mode.titleGuidance(guidance),"Evening drive","AUTO"))
        assertTrue(mode.navigationArtwork(guidance))
    }
    @Test fun bothKeepsTitleAndArtworkHints() {
        val mode=HintPlacement.BOTH
        assertSame(guidance,mode.titleGuidance(guidance))
        assertTrue(mode.navigationArtwork(guidance))
        assertTrue(mode.artworkLocation(true))
    }
    @Test fun noGuidanceAlwaysUsesMusicArtworkAndNoTitleHint() {
        for(mode in HintPlacement.entries){
            assertFalse(mode.navigationArtwork(null))
            assertEquals("Music",GuidanceFormatter.title(mode.titleGuidance(null),"Music","AUTO"))
            assertFalse(mode.needsLocation(true,null,false))
        }
    }
    @Test fun titleOnlyDoesNotRequestGpsForGoogleGuidance() {
        assertFalse(HintPlacement.TITLE_ONLY.needsLocation(true,guidance,false))
        assertFalse(HintPlacement.TITLE_ONLY.needsLocation(false,guidance,false))
    }
    @Test fun activeOwnRouteKeepsGpsInEveryPlacement() {
        for(mode in HintPlacement.entries){
            assertTrue(mode.needsLocation(true,guidance,true))
            assertTrue(mode.needsLocation(false,null,true))
        }
    }
    @Test fun disablingMapRetainsArrowArtworkWithoutMapLocation() {
        for(mode in listOf(HintPlacement.ARTWORK_ONLY,HintPlacement.BOTH)){
            assertTrue(mode.navigationArtwork(guidance))
            assertFalse(mode.artworkLocation(false))
            assertFalse(mode.needsLocation(false,guidance,false))
        }
    }
    @Test fun switchingModesDoesNotMutateNavigationOrItsSource() {
        val original=guidance.copy()
        for(mode in listOf(HintPlacement.BOTH,HintPlacement.TITLE_ONLY,HintPlacement.ARTWORK_ONLY,HintPlacement.BOTH)){
            mode.titleGuidance(guidance);mode.navigationArtwork(guidance)
            assertEquals(original,guidance)
        }
    }
}
