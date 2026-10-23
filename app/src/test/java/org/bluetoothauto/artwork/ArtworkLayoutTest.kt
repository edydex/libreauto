// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.junit.Assert.*
import org.junit.Test

class ArtworkLayoutTest {
    @Test fun directionTextIsAtBottomAndCreditIsAboveIt() {
        assertEquals(512f,ArtworkLayout.INSTRUCTION_BOTTOM,0f)
        assertTrue(ArtworkLayout.CREDIT_BOTTOM<ArtworkLayout.INSTRUCTION_TOP)
        assertTrue(ArtworkLayout.MAP_BOTTOM<ArtworkLayout.CREDIT_TOP)
        assertTrue(ArtworkLayout.DISTANCE_TOP>320f)
        assertTrue(ArtworkLayout.CREDIT_BOTTOM<ArtworkLayout.DISTANCE_TOP)
        assertTrue(ArtworkLayout.DISTANCE_BASELINE-ArtworkLayout.DISTANCE_TEXT_SIZE>=320f)
        assertTrue(ArtworkLayout.CREDIT_BASELINE in ArtworkLayout.CREDIT_TOP..ArtworkLayout.CREDIT_BOTTOM)
    }
    @Test fun twoInstructionLinesFitInsideBottomPanel() {
        val lastBaseline=ArtworkLayout.INSTRUCTION_BASELINE+ArtworkLayout.INSTRUCTION_TEXT_SIZE+7f
        assertTrue(ArtworkLayout.INSTRUCTION_BASELINE-ArtworkLayout.INSTRUCTION_TEXT_SIZE>=ArtworkLayout.INSTRUCTION_TOP)
        assertTrue(lastBaseline+6f<=ArtworkLayout.INSTRUCTION_BOTTOM)
    }
    @Test fun translucentCreditKeepsRequiredNamesAndDarkReadableInk() {
        assertTrue(ArtworkLayout.CREDITS.contains("© OpenMapTiles"))
        assertTrue(ArtworkLayout.CREDITS.contains("© OpenStreetMap contributors"))
        assertTrue((ArtworkLayout.CREDIT_BACKGROUND ushr 24) in 150..210)
        assertTrue((ArtworkLayout.CREDIT_INK ushr 24)>=230)
        assertTrue(ArtworkLayout.CREDIT_TEXT_SIZE>=18f)
    }
    @Test fun mapFittingWindowAvoidsHeaderAndCredit() {
        val top=ArtworkLayout.ROAD_CENTER_Y-ArtworkLayout.ROAD_VISIBLE_HEIGHT/2
        val bottom=ArtworkLayout.ROAD_CENTER_Y+ArtworkLayout.ROAD_VISIBLE_HEIGHT/2
        assertTrue(top>=ArtworkLayout.MAP_TOP+10)
        assertTrue(bottom<=ArtworkLayout.MAP_BOTTOM-10)
    }
}
