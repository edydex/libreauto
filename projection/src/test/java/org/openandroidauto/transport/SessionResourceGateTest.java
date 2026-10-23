package org.openandroidauto.transport;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionResourceGateTest {
    @Test public void cancelledFactoryClosesTheResourceReturnedLaterInsteadOfAdoptingIt() throws Exception {
        SessionResourceGate gate = new SessionResourceGate();
        CountDownLatch factoryEntered = new CountDownLatch(1), returnResource = new CountDownLatch(1);
        AtomicBoolean adopted = new AtomicBoolean(), closed = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread connect = new Thread(() -> {
            factoryEntered.countDown();
            try {
                if (!returnResource.await(3, TimeUnit.SECONDS)) throw new AssertionError();
                if (gate.publish(() -> closed.set(true))) gate.whileOpen(() -> adopted.set(true));
            } catch (Throwable error) { failure.set(error); }
        });
        connect.start();
        try {
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));
            assertTrue(gate.close()); returnResource.countDown(); connect.join(2_000);
            assertFalse(connect.isAlive()); assertNull(failure.get());
            assertTrue(closed.get()); assertFalse(adopted.get()); assertFalse(gate.isOpen());
        } finally { returnResource.countDown(); connect.join(2_000); gate.close(); }
    }

    @Test public void providerBlockedInCloseDoesNotLockOutCancellationOfLateResource() throws Exception {
        SessionResourceGate gate = new SessionResourceGate();
        CountDownLatch closing = new CountDownLatch(1), release = new CountDownLatch(1);
        assertTrue(gate.publish(() -> {
            closing.countDown(); if (!release.await(3, TimeUnit.SECONDS)) throw new IOException();
        }));
        Thread closer = new Thread(gate::close); closer.start();
        try {
            assertTrue(closing.await(1, TimeUnit.SECONDS));
            AtomicBoolean lateClosed = new AtomicBoolean();
            assertFalse(gate.publish(() -> lateClosed.set(true)));
            assertTrue(lateClosed.get());
            assertFalse(gate.whileOpen(() -> fail("Adopted after cancellation")));
        } finally { release.countDown(); closer.join(2_000); assertFalse(closer.isAlive()); }
    }

    @Test public void failedLateCloseRemainsOwnedForRetry() throws Exception {
        SessionResourceGate gate = new SessionResourceGate(); assertTrue(gate.close());
        AtomicInteger attempts = new AtomicInteger();
        AutoCloseable late = () -> { if (attempts.incrementAndGet() == 1) throw new IOException(); };
        try { gate.publish(late); fail("Expected first close failure"); } catch (IOException expected) { }
        assertTrue(gate.close()); assertEquals(2, attempts.get());
        assertFalse(gate.isOpen());
    }

    @Test public void oneFailedCloseStillClosesEveryOtherOwnedResource() throws Exception {
        SessionResourceGate gate = new SessionResourceGate(); AtomicInteger closed = new AtomicInteger();
        gate.publish(closed::incrementAndGet);
        gate.publish(() -> { throw new IOException(); });
        gate.publish(closed::incrementAndGet);
        assertFalse(gate.close()); assertEquals(2, closed.get()); assertFalse(gate.isOpen());
    }
}
