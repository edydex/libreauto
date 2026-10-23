package org.openandroidauto.media;

/** A native thread exit proof; cancellation and Runnable-finally notifications are not exit proofs. */
final class MediaWorkerLifetime {
    private final Thread worker;
    private boolean startRequested;
    private volatile boolean startReturned;
    private boolean closed;
    MediaWorkerLifetime(Runnable action, String name) { this(new Thread(action, name)); }
    MediaWorkerLifetime(Thread candidate) {
        worker = candidate;
        worker.setDaemon(true);
    }
    boolean start() {
        // Publish the start claim before Thread.start, without keeping any monitor through native
        // allocation. Stop is prompt; a published NEW thread cannot be mistaken for completed work.
        synchronized (this) {
            if (closed) return false;
            if (startRequested) return !startReturned || worker.isAlive();
            startRequested = true;
        }
        try { worker.start(); return true; }
        catch (OutOfMemoryError | IllegalThreadStateException refused) { return false; }
        finally { startReturned = true; }
    }
    void stop() {
        synchronized (this) { closed = true; }
        worker.interrupt();
    }
    void interrupt() { worker.interrupt(); }
    boolean isAlive() { synchronized (this) { return worker.isAlive(); } }
    /** Bounded off-main join. False leaves ownership intact and can be retried after provider release. */
    boolean awaitStopped(long timeoutMillis) {
        if (timeoutMillis < 0 || timeoutMillis > 60_000) throw new IllegalArgumentException("Bounded join required");
        synchronized (this) {
            if (!closed) return false;
            if (startRequested && !startReturned) return false;
            if (worker == Thread.currentThread()) return false;
        }
        boolean interrupted = Thread.interrupted();
        final long started = System.nanoTime();
        final long budget = timeoutMillis * 1_000_000L;
        try {
            while (worker.isAlive()) {
                long remaining = budget - (System.nanoTime() - started);
                if (remaining <= 0) return false;
                try { worker.join(remaining / 1_000_000L, (int) (remaining % 1_000_000L)); }
                catch (InterruptedException requested) { interrupted = true; }
            }
            return true;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
