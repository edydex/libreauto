// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.integration

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProjectionIntegrationTest {
    private var lease: ProjectionIntegration.Session? = null
    @After fun cleanUp() { lease?.finish(); ProjectionIntegration.delegate = null }

    @Test fun noHostDefaultsToOffEvenOnSupportedAndroid() {
        ProjectionIntegration.delegate = null
        assertFalse(ProjectionIntegration.isProjectionAllowed(32))
        assertFalse(ProjectionIntegration.isProjectionSelected(32))
        assertNull(ProjectionIntegration.beginSession(32))
        assertFalse(ProjectionIntegration.isSessionActive())
    }

    @Test fun olderPhoneCannotAcquireProjectionOrInvokeHostCallbacks() {
        val host = Host()
        ProjectionIntegration.delegate = host
        for (sdk in 26..31) {
            assertFalse(ProjectionIntegration.isProjectionAllowed(sdk))
            assertFalse(ProjectionIntegration.isProjectionSelected(sdk))
            assertNull(ProjectionIntegration.beginSession(sdk))
        }
        assertEquals(0, host.started)
        assertEquals(0, host.stopped)
        assertTrue(ProjectionAvailability.isSupported(32))
    }

    @Test fun setupCanRemainAvailableWhilePlayerStillOwnsAudio() {
        val host = Host().apply { allowed = false }
        ProjectionIntegration.delegate = host
        assertTrue(ProjectionIntegration.isProjectionSelected(32))
        assertFalse(ProjectionIntegration.isProjectionAllowed(32))
        assertNull(ProjectionIntegration.beginSession(32))
        assertEquals(0, host.started)
    }

    @Test fun sessionOwnershipIsReservedBeforeStartingCallbackAndReleasedOnce() {
        val host = Host().apply { starting = { assertTrue(ProjectionIntegration.isSessionActive()) } }
        ProjectionIntegration.delegate = host
        lease = ProjectionIntegration.beginSession(32)
        assertNotNull(lease)
        assertNull(ProjectionIntegration.beginSession(32))
        assertEquals(1, host.started)
        lease!!.finish(); lease!!.finish()
        assertFalse(ProjectionIntegration.isSessionActive())
        assertEquals(1, host.stopped)
    }

    @Test fun replacementHostCannotReceiveAnOldOwnersStopCallback() {
        val original = Host()
        ProjectionIntegration.delegate = original
        lease = ProjectionIntegration.beginSession(32)
        val replacement = Host()
        ProjectionIntegration.delegate = replacement
        lease!!.finish()
        assertEquals(1, original.stopped)
        assertEquals(0, replacement.stopped)
    }

    @Test fun carMetadataAndReadinessRequireAnActiveCapturedSession() {
        val original = Host()
        ProjectionIntegration.delegate = original
        ProjectionIntegration.reportCarSelected("12:34:56:78:90:AB")
        ProjectionIntegration.reportReady()
        assertNull(original.car)
        assertEquals(0, original.ready)
        lease = ProjectionIntegration.beginSession(32)
        val replacement = Host()
        ProjectionIntegration.delegate = replacement
        ProjectionIntegration.reportCarSelected("12:34:56:78:90:AB")
        ProjectionIntegration.reportReady()
        assertEquals("12:34:56:78:90:AB", original.car)
        assertEquals(1, original.ready)
        assertNull(replacement.car)
        assertEquals(0, replacement.ready)
        lease!!.finish()
        ProjectionIntegration.reportReady()
        assertEquals(1, original.ready)
    }

    @Test fun changingSelectionDuringHostHandoffAbortsBeforeSessionResources() {
        val host = Host().apply { starting = { allowed = false } }
        ProjectionIntegration.delegate = host
        assertNull(ProjectionIntegration.beginSession(32))
        assertFalse(ProjectionIntegration.isSessionActive())
        assertEquals(1, host.started)
        assertEquals(1, host.stopped)
    }

    @Test fun aBrokenHostGateOrCallbackFailsClosed() {
        ProjectionIntegration.delegate = object : ProjectionIntegration.Delegate {
            override fun isProjectionAllowed(): Boolean = error("unavailable")
            override fun onProjectionStarting() = error("must not start")
            override fun onProjectionStopped() = error("must not stop")
        }
        assertFalse(ProjectionIntegration.isProjectionAllowed(32))
        assertNull(ProjectionIntegration.beginSession(32))
        val host = Host().apply { starting = { error("cannot release player") } }
        ProjectionIntegration.delegate = host
        assertNull(ProjectionIntegration.beginSession(32))
        assertFalse(ProjectionIntegration.isSessionActive())
        assertEquals(1, host.stopped)
    }

    private class Host : ProjectionIntegration.Delegate {
        var allowed = true
        var started = 0
        var stopped = 0
        var starting: () -> Unit = {}
        var car: String? = null
        var ready = 0
        override fun isProjectionSelected() = true
        override fun isProjectionAllowed() = allowed
        override fun onProjectionStarting() { started++; starting() }
        override fun onProjectionStopped() { stopped++ }
        override fun onProjectionCarSelected(address: String?) { car = address }
        override fun onProjectionReady() { ready++ }
    }
}
