package org.openandroidauto.channel;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class RestartableChannelWorkerTest {
    @Test public void peerStopStartCoalescesOnlyAfterOldResourceTeardownOnSameOwner() throws Exception {
        CountDownLatch first = new CountDownLatch(1), inCleanup = new CountDownLatch(1), cleanupDone = new CountDownLatch(1), second = new CountDownLatch(1), endSecond = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger(); AtomicBoolean released = new AtomicBoolean(); AtomicReference<Thread> owner = new AtomicReference<>();
        RestartableChannelWorker worker = new RestartableChannelWorker("restart-test", generation -> {
            if (runs.incrementAndGet() == 1) {
                owner.set(Thread.currentThread()); first.countDown(); inCleanup.countDown(); await(cleanupDone); released.set(true);
            } else {
                assertTrue(released.get()); assertSame(owner.get(), Thread.currentThread()); second.countDown(); await(endSecond);
            }
            return true;
        });
        try {
            assertTrue(worker.requestStart()); assertTrue(first.await(1,TimeUnit.SECONDS)); worker.pause();
            assertTrue(worker.requestStart()); assertTrue(worker.requestStart()); assertTrue(inCleanup.await(1,TimeUnit.SECONDS));
            assertFalse(second.await(50,TimeUnit.MILLISECONDS)); assertEquals(1,runs.get());
            cleanupDone.countDown(); assertTrue(second.await(1,TimeUnit.SECONDS)); assertEquals(2,runs.get());
        } finally { worker.stop(); cleanupDone.countDown(); endSecond.countDown(); assertTrue(worker.awaitStopped()); }
        assertFalse(worker.requestStart());
    }
    @Test public void terminalStopCancelsPendingRestartAndWaitsForCleanup() throws Exception {
        CountDownLatch first = new CountDownLatch(1), cleanup = new CountDownLatch(1), joined = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger(); AtomicBoolean result = new AtomicBoolean();
        RestartableChannelWorker worker = new RestartableChannelWorker("restart-test", generation -> { runs.incrementAndGet(); first.countDown(); await(cleanup); return true; });
        assertTrue(worker.requestStart()); assertTrue(first.await(1,TimeUnit.SECONDS)); worker.pause(); assertTrue(worker.requestStart()); worker.stop();
        Thread waiter = new Thread(() -> { result.set(worker.awaitStopped()); joined.countDown(); }); waiter.start();
        assertFalse(joined.await(50,TimeUnit.MILLISECONDS)); cleanup.countDown(); assertTrue(joined.await(1,TimeUnit.SECONDS)); waiter.join();
        assertTrue(result.get()); assertEquals(1,runs.get()); assertFalse(worker.requestStart());
    }
    @Test public void unverifiedResourceReleasePreventsCleanupProofAndRestart() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        RestartableChannelWorker worker = new RestartableChannelWorker("restart-test", generation -> { ran.countDown(); return false; });
        assertTrue(worker.requestStart()); assertTrue(ran.await(1,TimeUnit.SECONDS));
        assertFalse(worker.awaitStopped()); assertFalse(worker.requestStart());
    }
    @Test public void workThatNeverReturnsVerifiedCleanupFailsClosed() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        RestartableChannelWorker worker = new RestartableChannelWorker("restart-test", generation -> { ran.countDown(); throw new IllegalStateException(); });
        assertTrue(worker.requestStart()); assertTrue(ran.await(1,TimeUnit.SECONDS));
        assertFalse(worker.awaitStopped()); assertFalse(worker.requestStart());
    }
    @Test public void nativeStartRefusalIsTerminalButHasNoInventedResourceLeak() {
        AtomicInteger runs = new AtomicInteger();
        RestartableChannelWorker worker = new RestartableChannelWorker("restart-test", generation -> { runs.incrementAndGet(); return true; },
            (action,name) -> new Thread(action,name) { @Override public synchronized void start() { throw new OutOfMemoryError(); } });
        assertFalse(worker.requestStart()); assertEquals(0,runs.get()); assertTrue(worker.awaitStopped()); assertFalse(worker.requestStart());
    }
    @Test public void peerPauseKeepsOneOwnerWaitingUntilTerminalJoin() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), exit = new CountDownLatch(1), joined = new CountDownLatch(1);
        RestartableChannelWorker worker = new RestartableChannelWorker("restart-test", generation -> { entered.countDown(); await(exit); return true; });
        assertTrue(worker.requestStart()); assertTrue(entered.await(1,TimeUnit.SECONDS)); worker.pause(); exit.countDown();
        Thread waiter = new Thread(() -> { assertTrue(worker.awaitStopped()); joined.countDown(); }); waiter.start();
        assertFalse(joined.await(50,TimeUnit.MILLISECONDS)); worker.stop(); assertTrue(joined.await(1,TimeUnit.SECONDS)); waiter.join();
    }
    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
