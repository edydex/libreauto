package org.openandroidauto

import org.junit.Assert.*
import org.junit.Test

class ProjectionForegroundPolicyTest {
    @Test fun fineOrApproximateGrantAllowsLocationWhenEnabled() {
        for ((fine, coarse) in listOf(true to false, false to true, true to true)) {
            val calls = mutableListOf<Boolean>()
            assertEquals(ProjectionForegroundPolicy.LocationMode.ENABLED,
                ProjectionForegroundPolicy.start(fine, coarse, true, calls::add))
            assertEquals(listOf(true), calls)
        }
    }

    @Test fun deniedPermissionKeepsProjectionEvenIfPhoneLocationIsOn() {
        for (enabled in listOf(true, false, null)) {
            val calls = mutableListOf<Boolean>()
            assertEquals(ProjectionForegroundPolicy.LocationMode.PERMISSION_MISSING,
                ProjectionForegroundPolicy.start(false, false, enabled, calls::add))
            assertEquals(listOf(false), calls)
        }
    }

    @Test fun disabledOrMissingLocationServiceNeverRequestsLocationType() {
        for ((enabled, expected) in listOf(false to ProjectionForegroundPolicy.LocationMode.DISABLED,
            null to ProjectionForegroundPolicy.LocationMode.UNAVAILABLE)) {
            val calls = mutableListOf<Boolean>()
            assertEquals(expected, ProjectionForegroundPolicy.start(true, true, enabled, calls::add))
            assertEquals(listOf(false), calls)
        }
    }

    @Test fun frameworkLocationDenialFallsBackToConnectedDeviceOnly() {
        val calls = mutableListOf<Boolean>()
        val mode = ProjectionForegroundPolicy.start(true, true, true) { location ->
            calls.add(location)
            if (location) throw SecurityException("synthetic grant revoked during promotion")
        }
        assertEquals(listOf(true, false), calls)
        assertEquals(ProjectionForegroundPolicy.LocationMode.START_REJECTED, mode)
    }

    @Test fun connectedDeviceFailureIsNotMistakenForSuccessfulProjection() {
        val failure = SecurityException("synthetic connected-device permission missing")
        val calls = mutableListOf<Boolean>()
        try {
            ProjectionForegroundPolicy.start(false, false, true) { location -> calls.add(location); throw failure }
            fail("Connected-device denial must propagate")
        } catch (caught: SecurityException) { assertSame(failure, caught) }
        assertEquals(listOf(false), calls)
    }

    @Test fun failedFallbackIsNotReportedAsAnActiveConnection() {
        val calls = mutableListOf<Boolean>()
        try {
            ProjectionForegroundPolicy.start(true, true, true) { location ->
                calls.add(location); throw SecurityException("synthetic framework denial")
            }
            fail("Denied fallback must propagate")
        } catch (_: SecurityException) { }
        assertEquals(listOf(true, false), calls)
    }

    @Test fun deniedPromotionStopsBeforeAnyTransportOrOwnershipCanStart() {
        val gate = ProjectionForegroundStartup()
        val events = mutableListOf<String>()
        assertFalse(gate.start({ throw SecurityException("synthetic promotion denial") },
            { it is SecurityException }, {
                events.add("inactive")
                assertFalse(gate.runIfReady { events.add("transport") })
                events.add("stop")
            }))
        assertFalse(gate.isReady)
        assertFalse(gate.runIfReady { events.add("transport") })
        assertEquals(listOf("inactive", "stop"), events)
    }

    @Test fun transportIsAllowedOnlyAfterSuccessfulForegroundPromotion() {
        val gate = ProjectionForegroundStartup()
        val events = mutableListOf<String>()
        assertTrue(gate.start({
            assertFalse(gate.runIfReady { events.add("early transport") })
            events.add("foreground")
        }, { it is SecurityException }, { fail("No denial expected") }))
        assertTrue(gate.runIfReady { events.add("transport") })
        assertEquals(listOf("foreground", "transport"), events)
    }

    @Test fun unrelatedProgrammingFailureIsNotReportedAsPermissionDenial() {
        val gate = ProjectionForegroundStartup()
        val failure = IllegalArgumentException("synthetic invalid configuration")
        try {
            gate.start({ throw failure }, { it is SecurityException }, { fail("Wrong denial classification") })
            fail("Unrelated error must propagate")
        } catch (caught: IllegalArgumentException) { assertSame(failure, caught) }
        assertFalse(gate.runIfReady { fail("Failed promotion must block transport") })
    }
}
