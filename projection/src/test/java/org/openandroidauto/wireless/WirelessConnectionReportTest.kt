package org.openandroidauto.wireless

import org.junit.Assert.*
import org.junit.Test

class WirelessConnectionReportTest {
    @Test fun reportIncludesOnlyTypedConnectionAndProjectionStages() {
        val report = WirelessConnectionReport.format("P STARTED 0\nB WAITING_NETWORK 120\nP ACTIVE 456")
        assertTrue(report.contains("0ms projection: STARTED"))
        assertTrue(report.contains("120ms connection: WAITING_NETWORK"))
        assertTrue(report.contains("456ms projection: ACTIVE"))
    }
    @Test fun corruptedOrFreeTextPreferencesCannotEnterReport() {
        val report = WirelessConnectionReport.format("B NETWORK_secret 12\nX FREE_WORDS 1\nP STARTED -1\nB CONNECTED 1 extra\nP ACTIVE 3")
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("FREE_WORDS"))
        assertFalse(report.contains("extra"))
        assertTrue(report.contains("3ms projection: ACTIVE"))
    }
    @Test fun oversizeAndImpossibleElapsedValuesAreRefused() {
        assertEquals("No valid wireless connection report.", WirelessConnectionReport.format("x".repeat(8193)))
        val report = WirelessConnectionReport.format("P ACTIVE 2592000001\nP ENDED 999999999999999999999")
        assertEquals("No wireless connection report yet.", report)
    }
    @Test fun outputHasBoundedEntryCountAndNeverAppendsOverflow() {
        val input = (0 until 100).joinToString("\n") { "B WAITING_NETWORK $it" }
        val report = WirelessConnectionReport.format(input)
        assertTrue(report.contains("39ms connection"))
        assertFalse(report.contains("40ms connection"))
        assertEquals(40, report.lineSequence().count { it.contains("ms connection:") })
    }
}
