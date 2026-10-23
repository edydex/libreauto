// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

/** One quiet route refresh per validated connection and local-route revision. No polling. */
public final class OnlineRouteRefreshPolicy {
    public static final long DEBOUNCE_MILLIS = 1500;
    public static final class Ticket {
        public final long routeRevision;
        private final long networkRevision;
        private Ticket(long routeRevision, long networkRevision) {
            this.routeRevision = routeRevision; this.networkRevision = networkRevision;
        }
    }
    private boolean online;
    private long networkRevision, connectedAt;
    private long attemptedRoute = Long.MIN_VALUE, attemptedNetwork = Long.MIN_VALUE;

    public void setOnline(boolean value, long now) {
        if (online == value) return;
        online = value; networkRevision++;
        if (value) connectedAt = now;
    }
    public boolean isOnline() { return online; }
    public long connectionRevision() { return networkRevision; }
    /** A failed online request that fell back locally waits for a new connection. */
    public void suppressCurrentConnection(long routeRevision) {
        attemptedRoute = routeRevision; attemptedNetwork = networkRevision;
    }
    public long delayMillis(long routeRevision, long now) {
        if (!online || attemptedRoute == routeRevision && attemptedNetwork == networkRevision) return -1;
        return Math.max(0, DEBOUNCE_MILLIS - Math.max(0, now - connectedAt));
    }
    public Ticket begin(long routeRevision, long now) {
        if (delayMillis(routeRevision, now) != 0) return null;
        suppressCurrentConnection(routeRevision);
        return new Ticket(routeRevision, networkRevision);
    }
    public boolean current(Ticket ticket, long routeRevision) {
        return ticket != null && online && ticket.routeRevision == routeRevision
                && ticket.networkRevision == networkRevision;
    }
}
