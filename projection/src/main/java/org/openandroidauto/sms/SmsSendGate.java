// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.sms;

import org.openandroidauto.calls.CallContact;

/** In-memory two-confirmation transaction: exact recipient, then current reviewed body, once. */
public final class SmsSendGate {
    public static final int MAX_BODY=1000;
    public static final class Ticket {
        public final long generation;
        public final CallContact contact;
        public final int subscriptionId;
        private Ticket(long generation,CallContact contact,int sub) {this.generation=generation;this.contact=contact;this.subscriptionId=sub;}
    }
    public static final class Dispatch {
        public final String number,body;
        public final int subscriptionId;
        private Dispatch(Ticket ticket,String body) {this.number=ticket.contact.dialNumber;this.body=body;this.subscriptionId=ticket.subscriptionId;}
    }
    public interface Gateway { void send(Dispatch dispatch); }
    private long generation;
    private Ticket pending;
    private boolean recipientConfirmed;
    private String draft="";
    public synchronized Ticket select(CallContact contact,int subscriptionId) {
        invalidate();
        if(contact==null || subscriptionId<0 || subscriptionId==Integer.MAX_VALUE) return null;
        pending=new Ticket(generation,contact,subscriptionId);return pending;
    }
    public synchronized boolean current(Ticket ticket) {return ticket!=null && ticket==pending && ticket.generation==generation;}
    public synchronized boolean confirmRecipient(Ticket ticket,CallContact fresh,int sub) {
        if(!current(ticket)) return false;
        if(sub!=ticket.subscriptionId || !ticket.contact.sameTarget(fresh)) {invalidate();return false;}
        recipientConfirmed=true;return true;
    }
    public synchronized boolean confirmed(Ticket ticket) {return current(ticket)&&recipientConfirmed;}
    public synchronized void setDraft(Ticket ticket,String body) {if(confirmed(ticket))draft=body==null?"":body;}
    /** A Send click consumes before the gateway call, even if the gateway throws. No automatic retry. */
    public boolean send(Ticket ticket,CallContact fresh,String visibleBody,int sub,
                                     boolean active,boolean allowed,boolean unlocked,Gateway gateway) {
        Dispatch dispatch;
        synchronized(this) {
        if(!confirmed(ticket)) return false;
        String body=draft;
        boolean valid=active&&allowed&&unlocked&&sub==ticket.subscriptionId&&ticket.contact.sameTarget(fresh)
                &&body.equals(visibleBody)&&validBody(body)&&gateway!=null;
        invalidate();
        if(!valid) return false;
        dispatch=new Dispatch(ticket,body);
        }
        gateway.send(dispatch);return true;
    }
    public synchronized void invalidate() {pending=null;recipientConfirmed=false;draft="";generation++;}
    public static boolean validBody(String body) {
        if(body==null||body.isBlank()||body.length()>MAX_BODY)return false;
        for(int i=0;i<body.length();i++)if(Character.isISOControl(body.charAt(i))&&body.charAt(i)!='\n'&&body.charAt(i)!='\t')return false;
        return true;
    }
}
