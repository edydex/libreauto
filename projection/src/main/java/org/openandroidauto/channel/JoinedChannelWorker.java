package org.openandroidauto.channel;

/** Actual native-thread lifetime, independent of a cancellation flag or a
 * Runnable finally block. No codec, Android API, executor, or auxiliary thread.
 * Start publication precedes Thread.start; a failed start never loses a possibly
 * live thread. All joins are off-main owner operations. */
final class JoinedChannelWorker {
    enum StartResult { STARTED, ALREADY_ACTIVE, ALLOCATION_FAILED, REFUSED }
    private Thread thread;

    synchronized StartResult start(Thread candidate) {
        if (thread != null && thread.isAlive()) return StartResult.ALREADY_ACTIVE;
        if (candidate.getState() != Thread.State.NEW) return StartResult.REFUSED;
        thread = candidate;
        // Keep the monitor through native start, so an awaiter cannot observe a
        // published NEW thread and declare completion before start has returned.
        try { candidate.start(); return StartResult.STARTED; }
        catch (OutOfMemoryError allocationFailure) { return StartResult.ALLOCATION_FAILED; }
        catch (IllegalThreadStateException refused) { return StartResult.REFUSED; }
    }
    synchronized boolean isActive() { return thread != null && thread.isAlive(); }
    void interrupt() {
        Thread current;
        synchronized (this) { current = thread; }
        if (current != null) current.interrupt();
    }
    /** True only after actual thread exit. Interruption requests cancellation but
     * cannot manufacture a cleanup proof: wait to completion and restore it.
     * A worker cannot join itself and returns false without losing its identity.
     * This deliberately has no timed-success or frozen-provider guarantee. */
    boolean awaitStopped() {
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                Thread current;
                synchronized (this) { current = thread; }
                if (current == null) return true;
                if (current == Thread.currentThread()) return false;
                while (current.isAlive()) {
                    try { current.join(); }
                    catch (InterruptedException requested) { interrupted = true; }
                }
                synchronized (this) {
                    if (thread == current) { thread = null; return true; }
                    // A replacement that started after the old thread exited is
                    // also owned. Do not claim all workers stopped while it lives.
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
