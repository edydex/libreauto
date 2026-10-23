// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public class OfflineAreaSelectionTest {
    @Test public void overlapsReturnAllCandidatesSmallestFirst() {
        List<OfflineAreaPolicy.Bounds> areas=Arrays.asList(OfflineAreaPolicy.square(47.4,-122.3,5),OfflineAreaPolicy.square(47.4,-122.3,1),OfflineAreaPolicy.square(47.4,-122.3,2));
        assertEquals(Arrays.asList(1,2,0),OfflineAreaSelection.containing(areas,47.4,-122.3));
    }
    @Test public void boundaryTapsAreSelectableAndOutsideTapsSelectNothing() {
        OfflineAreaPolicy.Bounds b=OfflineAreaPolicy.square(47.4,-122.3,1);
        assertEquals(Arrays.asList(0),OfflineAreaSelection.containing(Arrays.asList(b),b.north,b.east));
        assertTrue(OfflineAreaSelection.containing(Arrays.asList(b),b.north+0.001,b.east).isEmpty());
        assertTrue(OfflineAreaSelection.containing(Arrays.asList(b),Double.NaN,b.east).isEmpty());
    }
    @Test public void equalOverlappingAreasRetainStableOrder() {
        OfflineAreaPolicy.Bounds b=OfflineAreaPolicy.square(47.4,-122.3,1);
        assertEquals(Arrays.asList(0,1),OfflineAreaSelection.containing(Arrays.asList(b,b),47.4,-122.3));
    }
}
