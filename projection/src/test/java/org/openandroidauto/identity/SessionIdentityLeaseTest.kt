package org.openandroidauto.identity

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionIdentityLeaseTest {
    @Test fun stopIsDurableBeforeAnyWaitAndRepeatedStopIsIdempotent() = runBlocking {
        val stop = ProjectionStopSignal()
        assertTrue(stop.stop()); assertFalse(stop.stop()); assertTrue(stop.isStopped)
        withTimeout(500) { stop.awaitStop() }
    }
    @Test fun activeSessionWaitsForExplicitStopAndJoinedBlockingChildBeforeRelease() = runBlocking {
        val stop = ProjectionStopSignal(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val request = CompletableDeferred<Unit>(); val proof = AtomicBoolean(); val closed = AtomicBoolean()
        val job = async(Dispatchers.IO) {
            try {
                SessionIdentityLease.run(stop, { request.complete(Unit) }, proof::get, {}) {
                    try { coroutineScope {
                        launch(Dispatchers.IO) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                        awaitCancellation()
                    } } finally { proof.set(true) }
                }
            } finally { closed.set(true) }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            delay(80); assertFalse(request.isCompleted); assertFalse(closed.get())
            stop.stop(); withTimeout(1000) { request.await() }
            assertFalse(proof.get()); assertFalse(closed.get()); assertFalse(job.isCompleted)
            release.countDown()
            val result = withTimeout(1000) { job.await() }
            assertTrue(result.joined); assertTrue(result.cleanupVerified); assertTrue(closed.get())
        } finally { release.countDown(); stop.stop(); withContext(NonCancellable) { job.join() } }
    }
    @Test fun alreadyStoppedStillRunsBodyFinalizationWithoutStaleWork() = runBlocking {
        val stop = ProjectionStopSignal().also { it.stop() }; val proof = AtomicBoolean(); val calls = AtomicInteger()
        val result = withTimeout(1000) {
            SessionIdentityLease.run(stop, { calls.incrementAndGet() }, proof::get, {}) {
                try { awaitCancellation() } finally { proof.set(true) }
            }
        }
        assertTrue(proof.get()); assertTrue(result.joined); assertTrue(calls.get() >= 1)
    }
    @Test fun bodyFailureStillWaitsForItsCleanupAndRecordsFailure() = runBlocking {
        val stop = ProjectionStopSignal(); val cleanup = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val proof = AtomicBoolean(); val requested = AtomicBoolean()
        val job = async(Dispatchers.IO) {
            SessionIdentityLease.run(stop, { requested.set(true) }, proof::get, {}) {
                try { throw IllegalStateException("owned fixture") }
                finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await(); proof.set(true) } }
            }
        }
        try {
            withTimeout(1000) { cleanup.await() }; assertFalse(job.isCompleted); assertFalse(proof.get())
            release.complete(Unit)
            val result = withTimeout(1000) { job.await() }
            assertTrue(result.operationFailed); assertTrue(result.cleanupVerified); assertTrue(requested.get())
        } finally { release.complete(Unit); withContext(NonCancellable) { job.join() } }
    }
}
