// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.voice;
import java.util.ArrayDeque;
import java.util.Arrays;
/** At most 200ms pending PCM, plus one <=200ms borrowed IPC frame. Offer copies synchronously. */
public final class DictationPcmQueue implements AutoCloseable {
    private final ArrayDeque<byte[]> pending=new ArrayDeque<>();
    private int bytes; private boolean ready,closed;
    public enum Offer { ACCEPTED, CLOSED, NOT_READY, INVALID, OVERFLOW }
    public synchronized boolean ready() { if(closed) return false; ready=true; notifyAll(); return true; }
    public synchronized Offer offer(byte[] pcm,int rate) {
        if(closed) return Offer.CLOSED;
        if(!ready) return Offer.NOT_READY;
        if(!DictationProtocol.validPcm(pcm,rate)) return Offer.INVALID;
        if(bytes+pcm.length>6400 || pending.size()>=10) return Offer.OVERFLOW;
        pending.addLast(pcm.clone()); bytes+=pcm.length; notifyAll(); return Offer.ACCEPTED;
    }
    public synchronized byte[] take() throws InterruptedException {
        while(pending.isEmpty() && !closed) wait();
        byte[] out=pending.pollFirst(); if(out!=null) bytes-=out.length; return out;
    }
    public synchronized int queuedBytes() { return bytes; }
    @Override public synchronized void close() {
        closed=true; ready=false; byte[] old;
        while((old=pending.pollFirst())!=null) Arrays.fill(old,(byte)0);
        bytes=0; notifyAll();
    }
}
