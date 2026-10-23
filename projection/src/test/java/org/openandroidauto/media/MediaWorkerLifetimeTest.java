package org.openandroidauto.media;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class MediaWorkerLifetimeTest {
    @Test public void constructionStartsNoIdleDecoderAndClosePreventsALateStart() {
        AtomicInteger runs = new AtomicInteger();
        MediaWorkerLifetime owner = new MediaWorkerLifetime(runs::incrementAndGet, "test-lazy-media");
        assertFalse(owner.isAlive()); assertEquals(0, runs.get());
        assertFalse(owner.awaitStopped(0)); // Before close, a future start is still possible.
        owner.stop();
        assertTrue(owner.awaitStopped(0)); assertFalse(owner.start()); assertEquals(0, runs.get());
    }
    @Test public void interruptedStalledProviderNeverManufacturesAnExitProof() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread actual = new Thread(() -> { entered.countDown(); awaitIgnoringInterrupt(release); });
        MediaWorkerLifetime owner = new MediaWorkerLifetime(actual);
        assertTrue(owner.start()); assertTrue(entered.await(1, TimeUnit.SECONDS));
        owner.stop();
        long started = System.nanoTime();
        assertFalse(owner.awaitStopped(40));
        assertTrue("bounded wait", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        assertTrue(actual.isAlive());
        release.countDown(); assertTrue(owner.awaitStopped(1000)); assertFalse(actual.isAlive());
    }
    @Test public void aFinishedDecodeBodyStillOwnsTheThreadThroughBlockedFinallyCleanup() throws Exception {
        CountDownLatch body = new CountDownLatch(1), inCleanup = new CountDownLatch(1), cleanup = new CountDownLatch(1);
        MediaWorkerLifetime owner = new MediaWorkerLifetime(() -> {
            try { awaitIgnoringInterrupt(body); }
            finally { inCleanup.countDown(); awaitIgnoringInterrupt(cleanup); }
        }, "test-native-release");
        assertTrue(owner.start()); body.countDown(); assertTrue(inCleanup.await(1, TimeUnit.SECONDS));
        owner.stop(); assertFalse(owner.awaitStopped(30));
        cleanup.countDown(); assertTrue(owner.awaitStopped(1000));
    }
    @Test public void duplicateStartCoalescesAndExitedWorkerIsNeverRestarted() throws Exception {
        AtomicInteger runs = new AtomicInteger(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        MediaWorkerLifetime owner = new MediaWorkerLifetime(() -> {
            runs.incrementAndGet(); entered.countDown(); awaitIgnoringInterrupt(release);
        }, "test-single-owner");
        assertTrue(owner.start()); assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(owner.start()); assertEquals(1, runs.get());
        owner.stop(); release.countDown(); assertTrue(owner.awaitStopped(1000));
        assertFalse(owner.start()); assertEquals(1, runs.get());
    }
    @Test public void callerInterruptIsRestoredAfterABoundedWait() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        MediaWorkerLifetime owner = new MediaWorkerLifetime(() -> awaitIgnoringInterrupt(release), "test-interrupted-owner");
        assertTrue(owner.start()); owner.stop();
        AtomicBoolean result = new AtomicBoolean(true), restored = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt(); result.set(owner.awaitStopped(30));
            restored.set(Thread.currentThread().isInterrupted());
        });
        waiter.start(); waiter.join(1000);
        assertFalse(waiter.isAlive()); assertFalse(result.get()); assertTrue(restored.get());
        release.countDown(); assertTrue(owner.awaitStopped(1000));
    }
    @Test public void selfJoinRefusesUntilTheActualThreadReturns() throws Exception {
        AtomicReference<MediaWorkerLifetime> reference = new AtomicReference<>(); AtomicBoolean self = new AtomicBoolean(true);
        CountDownLatch proceed = new CountDownLatch(1), checked = new CountDownLatch(1);
        MediaWorkerLifetime owner = new MediaWorkerLifetime(() -> {
            awaitIgnoringInterrupt(proceed); self.set(reference.get().awaitStopped(10)); checked.countDown();
        }, "test-self-media");
        reference.set(owner); assertTrue(owner.start()); owner.stop(); proceed.countDown();
        assertTrue(checked.await(1, TimeUnit.SECONDS)); assertFalse(self.get()); assertTrue(owner.awaitStopped(1000));
    }
    @Test public void publishedNewThreadIsNotReportedStoppedBeforeNativeStartReturns() throws Exception {
        CountDownLatch inStart = new CountDownLatch(1), start = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread actual = new Thread(() -> awaitIgnoringInterrupt(release)) {
            @Override public synchronized void start() { inStart.countDown(); awaitIgnoringInterrupt(start); super.start(); }
        };
        MediaWorkerLifetime owner = new MediaWorkerLifetime(actual);
        Thread starter = new Thread(() -> assertTrue(owner.start())); starter.start();
        assertTrue(inStart.await(1, TimeUnit.SECONDS));
        owner.stop(); assertFalse(owner.awaitStopped(0));
        start.countDown(); starter.join(1000); assertFalse(starter.isAlive());
        assertFalse(owner.awaitStopped(20));
        release.countDown(); assertTrue(owner.awaitStopped(1000)); assertFalse(actual.isAlive());
    }
    @Test public void exceptionalStartAfterLaunchingStillRetainsTheNativeWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread actual = new Thread(() -> { entered.countDown(); awaitIgnoringInterrupt(release); }) {
            @Override public synchronized void start() { super.start(); throw new OutOfMemoryError("test allocation failure"); }
        };
        MediaWorkerLifetime owner = new MediaWorkerLifetime(actual);
        assertFalse(owner.start()); assertTrue(entered.await(1, TimeUnit.SECONDS));
        owner.stop(); assertFalse(owner.awaitStopped(20)); assertTrue(actual.isAlive());
        release.countDown(); assertTrue(owner.awaitStopped(1000)); assertFalse(actual.isAlive());
    }
    @Test public void allocationFailureBeforeStartingCanBeClosedWithoutAnIdleThread() {
        MediaWorkerLifetime owner = new MediaWorkerLifetime(new Thread() {
            @Override public synchronized void start() { throw new OutOfMemoryError("test allocation failure"); }
        });
        assertFalse(owner.start()); owner.stop(); assertTrue(owner.awaitStopped(0)); assertFalse(owner.isAlive());
    }
    @Test public void joinBudgetMustBeExplicitlyBounded() {
        MediaWorkerLifetime owner = new MediaWorkerLifetime(() -> {}, "test-bounded-budget"); owner.stop();
        assertThrows(IllegalArgumentException.class, () -> owner.awaitStopped(-1));
        assertThrows(IllegalArgumentException.class, () -> owner.awaitStopped(60_001));
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
