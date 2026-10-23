package org.openandroidauto.transport;

import java.util.ArrayList;
import java.util.List;

/** One connection attempt. A resource returned after cancellation is closed, never adopted.
 * Close work runs outside the monitor so a blocked provider cannot prevent late publication
 * from observing the closed token. Retain references so a failed close can be retried. */
final class SessionResourceGate {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private boolean closed;
    boolean publish(AutoCloseable resource) throws Exception {
        final boolean adopt;
        synchronized (this) {
            resources.add(resource);
            adopt = !closed;
        }
        if (adopt) return true;
        resource.close();
        return false;
    }
    synchronized boolean whileOpen(Runnable action) {
        if (closed) return false;
        action.run();
        return true;
    }
    synchronized boolean isOpen() { return !closed; }
    boolean close() {
        final List<AutoCloseable> snapshot;
        synchronized (this) { closed = true; snapshot = new ArrayList<>(resources); }
        boolean success = true;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            try { snapshot.get(i).close(); } catch (Exception failure) { success = false; }
        }
        return success;
    }
}
