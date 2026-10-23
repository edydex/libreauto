package org.openandroidauto.channel;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class JoinedChannelWorkerTest {
    @Test public void nativeStartAllocationFailureIsNarrowAndJoinable() {
        JoinedChannelWorker owner = new JoinedChannelWorker();
        Thread thread = new Thread() { @Override public synchronized void start() { throw new OutOfMemoryError(); } };
        assertEquals(JoinedChannelWorker.StartResult.ALLOCATION_FAILED, owner.start(thread));
        assertTrue(owner.awaitStopped()); assertFalse(owner.isActive());
    }
    @Test public void ordinaryStartRefusalAndUnrelatedErrorAreNotConfused() {
        JoinedChannelWorker owner = new JoinedChannelWorker();
        assertEquals(JoinedChannelWorker.StartResult.REFUSED, owner.start(new Thread() {
            @Override public synchronized void start() { throw new IllegalThreadStateException(); }
        }));
        assertTrue(owner.awaitStopped());
        assertThrows(AssertionError.class, () -> owner.start(new Thread() {
            @Override public synchronized void start() { throw new AssertionError(); }
        }));
        assertTrue(owner.awaitStopped());
    }
    @Test public void evenAStartThatThrowsAfterLaunchingKeepsTheLiveThreadOwned() throws Exception {
        JoinedChannelWorker owner = new JoinedChannelWorker();
        CountDownLatch release = new CountDownLatch(1), joined = new CountDownLatch(1);
        Thread candidate = new Thread(() -> await(release)) {
            @Override public synchronized void start() { super.start(); throw new OutOfMemoryError(); }
        };
        assertEquals(JoinedChannelWorker.StartResult.ALLOCATION_FAILED, owner.start(candidate));
        assertTrue(owner.isActive());
        Thread waiter = new Thread(() -> { assertTrue(owner.awaitStopped()); joined.countDown(); }); waiter.start();
        assertFalse(joined.await(50, TimeUnit.MILLISECONDS));
        release.countDown(); assertTrue(joined.await(1, TimeUnit.SECONDS)); waiter.join();
        assertFalse(candidate.isAlive());
    }
    @Test public void interruptDoesNotTurnAWorkerIgnoringCancellationIntoCompletion() throws Exception {
        JoinedChannelWorker owner = new JoinedChannelWorker();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), joined = new CountDownLatch(1);
        assertEquals(JoinedChannelWorker.StartResult.STARTED, owner.start(new Thread(() -> { entered.countDown(); await(release); })));
        assertTrue(entered.await(1, TimeUnit.SECONDS)); owner.interrupt();
        Thread waiter = new Thread(() -> { assertTrue(owner.awaitStopped()); joined.countDown(); }); waiter.start();
        assertFalse(joined.await(50, TimeUnit.MILLISECONDS));
        release.countDown(); assertTrue(joined.await(1, TimeUnit.SECONDS)); waiter.join();
    }
    @Test public void interruptedJoinWaitsForRealExitThenRestoresTheCallerInterrupt() throws Exception {
        JoinedChannelWorker owner = new JoinedChannelWorker(); CountDownLatch release = new CountDownLatch(1), joined = new CountDownLatch(1);
        owner.start(new Thread(() -> await(release))); AtomicBoolean restored = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt(); assertTrue(owner.awaitStopped());
            restored.set(Thread.currentThread().isInterrupted()); joined.countDown();
        }); waiter.start();
        assertFalse(joined.await(50, TimeUnit.MILLISECONDS));
        release.countDown(); assertTrue(joined.await(1, TimeUnit.SECONDS)); waiter.join(); assertTrue(restored.get());
    }
    @Test public void selfJoinRefusesWithoutLosingThreadOwnership() throws Exception {
        JoinedChannelWorker owner = new JoinedChannelWorker(); AtomicReference<Boolean> self = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        owner.start(new Thread(() -> { self.set(owner.awaitStopped()); ran.countDown(); }));
        assertTrue(ran.await(1, TimeUnit.SECONDS)); assertEquals(Boolean.FALSE, self.get()); assertTrue(owner.awaitStopped());
    }
    @Test public void bodyCompletionDoesNotSkipBlockedResourceFinallyCleanup() throws Exception {
        JoinedChannelWorker owner = new JoinedChannelWorker();
        CountDownLatch body = new CountDownLatch(1), cleanup = new CountDownLatch(1), releaseCleanup = new CountDownLatch(1), joined = new CountDownLatch(1);
        owner.start(new Thread(() -> {
            try { await(body); }
            finally { cleanup.countDown(); await(releaseCleanup); }
        }));
        body.countDown(); assertTrue(cleanup.await(1, TimeUnit.SECONDS));
        Thread waiter = new Thread(() -> { assertTrue(owner.awaitStopped()); joined.countDown(); }); waiter.start();
        assertFalse(joined.await(50, TimeUnit.MILLISECONDS));
        releaseCleanup.countDown(); assertTrue(joined.await(1, TimeUnit.SECONDS)); waiter.join();
    }
    @Test public void publishedNewThreadCannotBeMistakenForExitWhileStartIsPending() throws Exception {
        JoinedChannelWorker owner = new JoinedChannelWorker();
        CountDownLatch inStart = new CountDownLatch(1), permitStart = new CountDownLatch(1), release = new CountDownLatch(1), joined = new CountDownLatch(1);
        Thread candidate = new Thread(() -> await(release)) {
            @Override public synchronized void start() { inStart.countDown(); await(permitStart); super.start(); }
        };
        Thread starter = new Thread(() -> owner.start(candidate)); starter.start(); assertTrue(inStart.await(1, TimeUnit.SECONDS));
        Thread waiter = new Thread(() -> { assertTrue(owner.awaitStopped()); joined.countDown(); }); waiter.start();
        assertFalse(joined.await(50, TimeUnit.MILLISECONDS)); permitStart.countDown(); starter.join();
        assertFalse(joined.await(50, TimeUnit.MILLISECONDS)); release.countDown(); assertTrue(joined.await(1, TimeUnit.SECONDS)); waiter.join();
    }
    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
