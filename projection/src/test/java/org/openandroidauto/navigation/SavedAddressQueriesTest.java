// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
public class SavedAddressQueriesTest {
    @Test public void fullUnitAddressIsFirstAndBuildingFallbackDoesNotChangeOriginal() {
        String original="123 Example Ave #7109, Example City, WA 98000";
        assertEquals(List.of(original,"123 Example Ave, Example City, WA 98000"),SavedAddressQueries.forAddress(original));
        assertTrue(original.contains("#7109"));
        assertEquals(List.of("12 Main St Suite 4","12 Main St"),SavedAddressQueries.forAddress("12 Main St Suite 4"));
    }
    @Test public void ordinaryStreetNumbersAreNeverStrippedAndVariantsAreBounded() {
        assertEquals(List.of("123 24th Ave S, Example City"),SavedAddressQueries.forAddress("123 24th Ave S, Example City"));
        assertEquals(2,SavedAddressQueries.forAddress("123 Main St apt 4B, City").size());
    }
    @Test public void invalidOrOversizedQueriesRefuseBeforeAnyLookup() {
        for(String value:new String[]{null,"x"," ","x\ny","x".repeat(301)})try{SavedAddressQueries.forAddress(value);fail();}catch(IllegalArgumentException expected){}
    }
}
