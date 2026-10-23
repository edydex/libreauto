// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.junit.Assert.*
import org.junit.Test

class OfficialProjectionProtocolTest {
    private val google = OfficialProjectionProtocol.PROVIDER_PACKAGE
    @Test fun onlyTheTrustedProjectionValueMeansOfficialProjection() {
        assertEquals(true, OfficialProjectionProtocol.projected(google, 2))
        assertEquals(false, OfficialProjectionProtocol.projected(google, 0))
        assertEquals(false, OfficialProjectionProtocol.projected(google, 1))
    }
    @Test fun anotherProviderCannotSpoofProjectionOrDisconnection() {
        for (packageName in listOf(null, "untrusted.provider", "org.bluetoothauto"))
            for (value in 0..2) assertNull(OfficialProjectionProtocol.projected(packageName, value))
    }
    @Test fun missingAndFutureValuesAreUnknown() {
        for (value in listOf(null, -1, 3, Int.MAX_VALUE)) assertNull(OfficialProjectionProtocol.projected(google, value))
    }
    @Test fun initialStateDoesNotClaimKnowledgeOrProjection() {
        val state = OfficialProjectionObservation()
        assertFalse(state.known); assertFalse(state.active)
    }
    @Test fun confirmedProjectionIsRetainedThroughTemporaryQueryFailure() {
        val state = OfficialProjectionObservation()
        assertTrue(state.accept(state.begin(), true))
        assertFalse(state.accept(state.begin(), null))
        assertTrue(state.known); assertTrue(state.active)
    }
    @Test fun validDisconnectedObservationClearsProjection() {
        val state = OfficialProjectionObservation()
        state.accept(state.begin(), true)
        assertTrue(state.accept(state.begin(), false))
        assertTrue(state.known); assertFalse(state.active)
    }
    @Test fun olderQueryCannotOverwriteMoreRecentObservation() {
        val state = OfficialProjectionObservation()
        val older = state.begin(); val newer = state.begin()
        assertTrue(state.accept(newer, true))
        assertFalse(state.accept(older, false))
        assertTrue(state.active)
    }
    @Test fun closingTheLastOwnerRejectsEveryLateResult() {
        val state = OfficialProjectionObservation()
        val ticket = state.begin(); state.accept(ticket, true); state.close()
        assertFalse(state.accept(ticket, true)); assertFalse(state.accept(state.begin(), true))
        assertFalse(state.known); assertFalse(state.active)
    }
    @Test fun repeatingSameKnownValueDoesNotCauseHeavyReconfiguration() {
        val state = OfficialProjectionObservation()
        assertTrue(state.accept(state.begin(), true))
        assertFalse(state.accept(state.begin(), true))
    }
    @Test fun initialUnavailableResultRemainsUnknownAndInactive() {
        val state = OfficialProjectionObservation()
        assertFalse(state.accept(state.begin(), null))
        assertFalse(state.known); assertFalse(state.active)
    }
}
