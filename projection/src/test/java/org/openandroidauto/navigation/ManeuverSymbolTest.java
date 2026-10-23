// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Numeric codes are the external Valhalla native-JSON contract, not translated text guesses. */
public class ManeuverSymbolTest {
    @Test public void turnsUseTheirActualDirectionAndAngle() {
        assertEquals(ManeuverSymbol.SLIGHT_RIGHT, symbol(9));
        assertEquals(ManeuverSymbol.RIGHT, symbol(10));
        assertEquals(ManeuverSymbol.SHARP_RIGHT, symbol(11));
        assertEquals(ManeuverSymbol.UTURN_RIGHT, symbol(12));
        assertEquals(ManeuverSymbol.UTURN_LEFT, symbol(13));
        assertEquals(ManeuverSymbol.SHARP_LEFT, symbol(14));
        assertEquals(ManeuverSymbol.LEFT, symbol(15));
        assertEquals(ManeuverSymbol.SLIGHT_LEFT, symbol(16));
    }
    @Test public void startSideDoesNotInventATurn() {
        for (int type : new int[] {1, 2, 3, 7, 8, 17, 22})
            assertEquals(ManeuverSymbol.STRAIGHT, symbol(type));
    }
    @Test public void destinationAndConfirmedArrivalUseFlag() {
        for (int type : new int[] {4, 5, 6}) assertEquals(ManeuverSymbol.DESTINATION, symbol(type));
        assertEquals(ManeuverSymbol.DESTINATION, ManeuverSymbol.fromValhalla(10, true));
        assertEquals(ManeuverSymbol.DESTINATION, ManeuverSymbol.fromValhalla(Integer.MAX_VALUE, true));
    }
    @Test public void rampsExitsAndForksKeepTheirSide() {
        for (int type : new int[] {18, 20}) assertEquals(ManeuverSymbol.EXIT_RIGHT, symbol(type));
        for (int type : new int[] {19, 21}) assertEquals(ManeuverSymbol.EXIT_LEFT, symbol(type));
        assertEquals(ManeuverSymbol.KEEP_RIGHT, symbol(23));
        assertEquals(ManeuverSymbol.KEEP_LEFT, symbol(24));
    }
    @Test public void mergesKeepTheDocumentedDirection() {
        assertEquals(ManeuverSymbol.MERGE, symbol(25));
        assertEquals(ManeuverSymbol.MERGE_RIGHT, symbol(37));
        assertEquals(ManeuverSymbol.MERGE_LEFT, symbol(38));
    }
    @Test public void roundaboutAndFerryNeverPretendToBeOrdinaryTurns() {
        assertEquals(ManeuverSymbol.ROUNDABOUT, symbol(26));
        assertEquals(ManeuverSymbol.ROUNDABOUT, symbol(27));
        assertEquals(ManeuverSymbol.FERRY, symbol(28));
        assertEquals(ManeuverSymbol.FERRY, symbol(29));
    }
    @Test public void unsupportedOrFutureTypesDoNotGuessStraightAhead() {
        for (int type : new int[] {-1, 0, 30, 31, 32, 33, 34, 35, 36, 39, 40, 41, 42, 43, Integer.MAX_VALUE})
            assertEquals(ManeuverSymbol.UNKNOWN, symbol(type));
    }
    private static ManeuverSymbol symbol(int type) { return ManeuverSymbol.fromValhalla(type, false); }
}
