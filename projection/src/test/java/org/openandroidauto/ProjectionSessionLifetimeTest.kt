package org.openandroidauto

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProjectionSessionLifetimeTest {
    @Test fun stoppedSessionKeepsOwnershipUntilItsBlockedChildReallyLeaves() = runBlocking {
        val lifetime = ProjectionSessionLifetime()
        assertTrue(lifetime.acquire()); assertTrue(lifetime.begin())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val joined = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            coroutineScope {
                launch {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                awaitCancellation()
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val monitor = launch {
            job.join()
            assertTrue(lifetime.completeAfterJoin(true))
            joined.complete(Unit)
        }
        try {
            lifetime.requestStop(); job.cancel()
            assertFalse(lifetime.accepting)
            assertFalse(job.isActive)
            assertFalse(job.isCompleted)
            assertFalse(joined.isCompleted)
            assertFalse(ProjectionSessionLifetime().acquire())
        } finally { release.countDown() }
        withTimeout(2_000) { monitor.join() }
        val next = ProjectionSessionLifetime()
        assertTrue(next.acquire()); next.requestStop(); assertTrue(next.completeAfterJoin(true))
    }

    @Test fun cancellingFullQueueReleasesBlockedProducerWithoutPartialActiveDrop() {
        val outbound = ProjectionOutbound<Int>(1, 1)
        outbound.send(1, false)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val producer = Thread {
            started.countDown()
            try { outbound.send(2, false) } catch (failure: Throwable) { error.set(failure) }
            finally { finished.countDown() }
        }
        producer.start()
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            outbound.close()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            producer.join(2_000)
            assertFalse(producer.isAlive); assertNull(error.get())
            outbound.send(3, false) // Closed admission is a no-op, not a stranded new producer.
        } finally { outbound.close(); producer.interrupt(); producer.join(2_000) }
    }

    @Test fun activeBackpressurePreservesAllOrderedFramesAndControlPriority() = runBlocking {
        val outbound = ProjectionOutbound<Int>(1, 1)
        val sent = async(Dispatchers.IO) { repeat(30) { outbound.send(it, false) } }
        val received = mutableListOf<Int>()
        repeat(30) { received += withTimeout(2_000) { outbound.receive() }; yield() }
        sent.await()
        assertEquals((0 until 30).toList(), received)
        outbound.send(40, false); outbound.send(50, true)
        assertEquals(50, outbound.receive()); assertEquals(40, outbound.receive())
        outbound.close()
    }

    @Test fun closingAnEmptyQueueWakesItsWriter() = runBlocking {
        val outbound = ProjectionOutbound<Int>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { outbound.receive() }
        assertFalse(waiting.isCompleted)
        outbound.close()
        try { withTimeout(2_000) { waiting.await() }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }
}
