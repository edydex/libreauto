// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.calls;

/** Single-use confirmation bound to an immutable selected contact row and panel generation. */
public final class CallConfirmationGate {
    public static final class Ticket {
        public final long generation;
        public final CallContact contact;
        private Ticket(long generation, CallContact contact) { this.generation=generation; this.contact=contact; }
    }
    private long generation;
    private Ticket pending;
    public synchronized Ticket select(CallContact contact) {
        if (contact==null) throw new IllegalArgumentException("Missing contact");
        pending=new Ticket(++generation,contact); return pending;
    }
    public synchronized void invalidate() { generation++; pending=null; }
    public synchronized boolean isCurrent(Ticket ticket) { return ticket!=null && pending==ticket && ticket.generation==generation; }
    public synchronized boolean consume(Ticket ticket, CallContact current, boolean projectionActive,
                                         boolean enabled, boolean permissions) {
        if (!isCurrent(ticket)) return false;
        pending=null; generation++;
        return projectionActive && enabled && permissions && ticket.contact.sameTarget(current);
    }
}
