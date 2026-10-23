package org.openandroidauto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionReadinessDeadlineTest {
    @Test fun silentConnectedPeerExpiresOnceAtTheDeadline() = runTest {
        var failures = 0
        val gate = ProjectionReadinessDeadline(this, 1000) { failures++ }
        gate.start(); gate.start(); runCurrent()
        advanceTimeBy(999); runCurrent(); assertEquals(0, failures)
        advanceTimeBy(1); runCurrent(); assertEquals(1, failures)
        assertFalse(gate.ready()); gate.start(); advanceTimeBy(1000); runCurrent(); assertEquals(1, failures)
    }
    @Test fun readyBeforeTimerStartNeverSchedulesFailure() = runTest {
        var failures = 0
        val gate = ProjectionReadinessDeadline(this, 1000) { failures++ }
        assertTrue(gate.ready()); assertTrue(gate.ready()); gate.start(); advanceTimeBy(2000); runCurrent(); assertEquals(0, failures)
    }
    @Test fun readinessBeforeDeadlineReleasesWaitingTimer() = runTest {
        var failures = 0
        val gate = ProjectionReadinessDeadline(this, 1000) { failures++ }
        gate.start(); runCurrent(); advanceTimeBy(999); gate.ready(); runCurrent()
        advanceTimeBy(2000); runCurrent(); assertEquals(0, failures)
    }
    @Test fun closingBeforeOrAfterStartSuppressesTimeout() = runTest {
        var failures = 0
        val before = ProjectionReadinessDeadline(this, 1000) { failures++ }
        before.close(); before.start(); assertFalse(before.ready())
        val after = ProjectionReadinessDeadline(this, 1000) { failures++ }
        after.start(); runCurrent(); after.close(); after.close(); assertFalse(after.ready())
        advanceTimeBy(2000); runCurrent(); assertEquals(0, failures)
    }
    @Test fun serviceScopeCancellationDoesNotReportTimeout() = runTest {
        var failures = 0
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = ProjectionReadinessDeadline(scope, 1000) { failures++ }
        gate.start(); runCurrent(); scope.cancel()
        advanceTimeBy(2000); runCurrent(); assertEquals(0, failures)
    }
}
