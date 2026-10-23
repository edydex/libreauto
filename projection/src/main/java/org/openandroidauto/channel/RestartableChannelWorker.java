package org.openandroidauto.channel;

/** One persistent, joined native owner. Peer pause/start coalesces work on this
 * same thread AFTER the old work has returned a verified resource teardown.
 * Only terminal stop exits the owner; no extra coordinator or polling thread. */
final class RestartableChannelWorker {
    interface Work { boolean run(long generation) throws Exception; }
    interface ThreadFactory { Thread create(Runnable action, String name); }
    private final Object lock = new Object();
    private final JoinedChannelWorker nativeWorker = new JoinedChannelWorker();
    private final String name;
    private final Work work;
    private final ThreadFactory factory;
    private volatile boolean wanted, closed;
    private volatile long generation;
    private volatile boolean cleanupVerified = true;

    RestartableChannelWorker(String name, Work work) {
        this(name, work, (action, title) -> { Thread thread = new Thread(action, title); thread.setDaemon(true); return thread; });
    }
    RestartableChannelWorker(String name, Work work, ThreadFactory factory) {
        this.name = name; this.work = work; this.factory = factory;
    }
    boolean requestStart() {
        synchronized (lock) {
            if (closed) return false;
            if (wanted) return true; // Coalesce duplicate start notifications.
            wanted = true; generation++;
            if (!nativeWorker.isActive()) {
                Thread actual = factory.create(this::loop, name);
                if (nativeWorker.start(actual) != JoinedChannelWorker.StartResult.STARTED) {
                    closed = true; wanted = false; generation++; lock.notifyAll();
                    nativeWorker.interrupt();
                    return false;
                }
            }
            lock.notifyAll();
            return true;
        }
    }
    void pause() {
        synchronized (lock) {
            if (closed) return;
            wanted = false; generation++; lock.notifyAll();
        }
        // Do not leave the persistent owner interrupted between peer sessions.
        // The media owner must wake ACK waits; codec dequeue already has a bound.
    }
    void stop() {
        synchronized (lock) { closed = true; wanted = false; generation++; lock.notifyAll(); }
        nativeWorker.interrupt();
    }
    boolean isCurrent(long candidate) { return wanted && !closed && generation == candidate; }
    boolean isClosed() { return closed; }
    boolean isActive() { return nativeWorker.isActive(); }
    boolean awaitStopped() { return nativeWorker.awaitStopped() && cleanupVerified; }
    private void loop() {
        try {
            while (true) {
                final long current;
                synchronized (lock) {
                    while (!closed && !wanted) lock.wait();
                    if (closed) return;
                    current = generation;
                }
                boolean verified = false;
                try { verified = work.run(current); }
                finally {
                    synchronized (lock) {
                        if (!verified) { cleanupVerified = false; closed = true; wanted = false; generation++; }
                        else if (generation == current) wanted = false;
                        lock.notifyAll();
                    }
                }
            }
        } catch (InterruptedException requested) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { cleanupVerified = false; }
        finally {
            synchronized (lock) { closed = true; wanted = false; generation++; lock.notifyAll(); }
        }
    }
}
