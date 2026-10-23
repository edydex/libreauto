// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.junit.Assert.*
import org.junit.Test

class CarDisplayTransitionTest {
    @Test fun audioCannotBeConsideredStoppedBeforeProjectionCallback() {
        val ticket = CarDisplayTransition()
        assertFalse(ticket.acceptPlaybackResult())
        assertTrue(ticket.acceptProjectionResult())
        assertTrue(ticket.acceptPlaybackResult())
        assertTrue(ticket.finish())
    }
    @Test fun duplicateProjectionCallbacksCannotStopPlaybackTwice() {
        val ticket = CarDisplayTransition()
        assertTrue(ticket.acceptProjectionResult())
        assertFalse(ticket.acceptProjectionResult())
        assertTrue(ticket.acceptPlaybackResult())
    }
    @Test fun duplicatePlaybackCallbacksCannotApplyTheAaPackageChangeTwice() {
        val ticket = CarDisplayTransition()
        assertTrue(ticket.acceptProjectionResult())
        assertTrue(ticket.acceptPlaybackResult())
        assertFalse(ticket.acceptPlaybackResult())
        assertFalse(ticket.acceptProjectionResult())
    }
    @Test fun timingOutAtEveryPhaseMakesAllLateCallbacksInert() {
        (0..2).forEach { phase ->
            val ticket = CarDisplayTransition()
            if (phase >= 1) assertTrue(ticket.acceptProjectionResult())
            if (phase >= 2) assertTrue(ticket.acceptPlaybackResult())
            assertTrue(ticket.finish())
            assertTrue(ticket.complete)
            assertFalse(ticket.acceptProjectionResult())
            assertFalse(ticket.acceptPlaybackResult())
            assertFalse(ticket.finish())
        }
    }
    @Test fun stopDeadlineCanCloseEitherIncompleteTeardownPhase() {
        (0..1).forEach { completedStops ->
            val ticket = CarDisplayTransition()
            if (completedStops == 1) assertTrue(ticket.acceptProjectionResult())
            assertTrue(ticket.stopTimedOut())
            assertTrue(ticket.complete)
            assertFalse(ticket.acceptProjectionResult())
            assertFalse(ticket.acceptPlaybackResult())
        }
    }
    @Test fun aDelayedStopDeadlineCannotReleaseTheGateWhileHelperMutationIsPending() {
        val ticket = CarDisplayTransition()
        assertTrue(ticket.acceptProjectionResult())
        assertTrue(ticket.acceptPlaybackResult())
        // A queued old stop timer must remain inert even if it fires after its removal races.
        assertFalse(ticket.stopTimedOut())
        assertFalse(ticket.complete)
        assertFalse(ticket.stopTimedOut())
        // Only the helper result (success, failure or its own timeout) completes this phase.
        assertTrue(ticket.finish())
        assertTrue(ticket.complete)
        assertFalse(ticket.stopTimedOut())
        assertFalse(ticket.finish())
    }
}
