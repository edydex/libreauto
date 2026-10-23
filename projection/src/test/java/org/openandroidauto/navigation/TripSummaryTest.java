package org.openandroidauto.navigation;

import org.junit.Test;
import static org.junit.Assert.*;

public class TripSummaryTest {
    @Test public void durationIsReadableAndRoundsPartialMinutesUp() {
        assertEquals("0 min", TripSummary.duration(0));
        assertEquals("1 min", TripSummary.duration(1));
        assertEquals("24 min", TripSummary.duration(1439));
        assertEquals("1 h", TripSummary.duration(3599));
        assertEquals("1 h 1 min", TripSummary.duration(3601));
        assertEquals("1 d 2 h", TripSummary.duration(26 * 3600));
    }
    @Test public void arrivalUsesRemainingDurationWithoutDoubleCountingDelays() {
        assertEquals(125000, TripSummary.arrivalMillis(5000, 120));
        assertEquals(5000, TripSummary.arrivalMillis(5000, 0));
    }
    @Test public void unavailableOrOverflowNeverDisplaysAClock() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 31 * 86400}) {
            assertNull(TripSummary.duration(invalid)); assertEquals(-1, TripSummary.arrivalMillis(5000, invalid));
        }
        assertEquals(-1, TripSummary.arrivalMillis(Long.MAX_VALUE - 1, 2));
    }
}
