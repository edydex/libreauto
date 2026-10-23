// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

/** Each network exchange is reserved durably before it can reach the tile provider. */
public final class OfflineRequestBudget {
    public static final long MAX_REQUESTS = 2700;
    public interface Backend { long used(); boolean store(long value); }
    private final Backend backend;
    public OfflineRequestBudget(Backend backend) { this.backend=backend; }
    public synchronized boolean reserve() {
        try {
            long used=backend.used();
            return used>=0&&used<MAX_REQUESTS&&backend.store(used+1);
        } catch(RuntimeException error) { return false; }
    }
    public synchronized long used() { return backend.used(); }
}
