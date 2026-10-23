// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.channel;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Bounded protocol-to-Main handoff. No thread or timer is created here.
 * offerOwned always consumes the supplied array, even on refusal. The Main sink
 * borrows it only for its synchronous call and must copy before retaining it.
 * Close wipes pending arrays immediately; an already borrowed array is wiped on
 * return. It never waits for Main/native work. Existing Main teardown acknowledgement
 * must therefore still precede resource-release proof. Queue + borrowed input
 * together remain <=6400 bytes/10 chunks, never one unbounded Handler post per packet. */
public final class CarPcmIngress implements AutoCloseable {
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_BYTES = 6400;
    public static final int MAX_CHUNKS = 10;
    private static final int DRAIN_BATCH = 10;
    public enum Offer { ACCEPTED, IGNORED, FAILED, POST_FAILED }
    public enum Failure { OVERFLOW, INVALID_AUDIO, SINK_FAILED }
    /** Must enqueue asynchronously, never invoke the task inline. */
    public interface Scheduler { boolean post(Runnable task); }
    public interface Sink { void accept(byte[] borrowedPcm, int sampleRate); }
    public interface FailureSink { void failed(long attempt, Failure reason); }
    private static final class Packet {
        final long attempt, epoch;
        final byte[] bytes;
        Packet(long attempt, long epoch, byte[] bytes) { this.attempt=attempt; this.epoch=epoch; this.bytes=bytes; }
    }
    private final Object lock = new Object();
    private final Scheduler scheduler;
    private final Sink sink;
    private final FailureSink failureSink;
    private final ArrayDeque<Packet> pending = new ArrayDeque<>();
    private final Runnable drain = this::drain;
    private long attempt, epoch;
    private boolean active, open, failed, closed, scheduled;
    private Failure failure;
    private long failureAttempt;
    private int retainedBytes, retainedChunks;

    public CarPcmIngress(Scheduler scheduler, Sink sink, FailureSink failureSink) {
        this.scheduler=scheduler; this.sink=sink; this.failureSink=failureSink;
    }
    public long beginAttempt() { synchronized(lock) {
        if (closed) return 0;
        attempt++; epoch++; active=true; open=false; failed=false; failure=null; clearPending();
        return attempt;
    } }
    public void endAttempt() { synchronized(lock) {
        attempt++; epoch++; active=false; open=false; failed=false; failure=null; clearPending();
    } }
    /** Called only from actual readiness/open transitions, never from transport setup. */
    public void setOpen(boolean value) { synchronized(lock) {
        if (closed || !active || failed) return;
        if (open == value) return;
        epoch++; open=value; clearPending();
    } }
    /** Capture immediately at receive; a concurrent close/reset invalidates this token. */
    public long openEpoch() { synchronized(lock) { return active && open && !failed && !closed ? epoch : 0; } }
    public boolean isFailedAttempt(long token) { synchronized(lock) { return active && failed && !closed && attempt==token; } }
    public Offer offerOwned(long token, byte[] bytes, int sampleRate) {
        if (bytes == null) return invalidAudio(token);
        synchronized(lock) {
            if (closed || !active || !open || failed || token==0 || token!=epoch) { wipe(bytes); return Offer.IGNORED; }
            if (sampleRate!=SAMPLE_RATE || bytes.length==0 || (bytes.length&1)!=0 || bytes.length>MAX_BYTES) {
                wipe(bytes); return failLocked(Failure.INVALID_AUDIO);
            }
            if (retainedBytes+bytes.length>MAX_BYTES || retainedChunks>=MAX_CHUNKS) {
                wipe(bytes); return failLocked(Failure.OVERFLOW);
            }
            pending.addLast(new Packet(attempt,epoch,bytes)); retainedBytes+=bytes.length; retainedChunks++;
            return scheduleLocked() ? Offer.ACCEPTED : Offer.POST_FAILED;
        }
    }
    /** Input validation failed before allocating/copying a PCM array. */
    public Offer invalidAudio(long token) { synchronized(lock) {
        return active && open && !failed && !closed && token!=0 && token==epoch
            ? failLocked(Failure.INVALID_AUDIO) : Offer.IGNORED;
    } }
    private Offer failLocked(Failure reason) {
        failed=true; open=false; epoch++; clearPending(); failure=reason; failureAttempt=attempt;
        return scheduleLocked() ? Offer.FAILED : Offer.POST_FAILED;
    }
    private boolean scheduleLocked() {
        if (scheduled) return true;
        scheduled=true;
        boolean posted;
        try { posted=scheduler.post(drain); } catch(RuntimeException failure) { posted=false; }
        if (!posted) { closed=true; active=false; open=false; failure=null; clearPending(); scheduled=false; }
        return posted;
    }
    private void drain() {
        try {
            for (int i=0;i<DRAIN_BATCH;i++) {
                Packet packet;
                Failure report;
                long reportAttempt;
                synchronized(lock) {
                    if (closed) return;
                    report=failure; reportAttempt=failureAttempt; failure=null;
                    packet=report==null ? pending.pollFirst() : null;
                    if (report==null && packet==null) return;
                }
                if (report!=null) { failureSink.failed(reportAttempt,report); continue; }
                try {
                    boolean valid;
                    synchronized(lock) { valid=active && open && !failed && !closed && packet.attempt==attempt && packet.epoch==epoch; }
                    // Main owns both this sink and assistant-turn changes. An off-Main
                    // close may invalidate an already borrowed call; it never wipes
                    // borrowed memory concurrently or waits for that call to return.
                    if (valid) sink.accept(packet.bytes,SAMPLE_RATE);
                } catch(RuntimeException callbackFailure) {
                    synchronized(lock) { if (active && !failed && !closed && packet.attempt==attempt) failLocked(Failure.SINK_FAILED); }
                } finally { synchronized(lock) { wipe(packet.bytes); retainedBytes-=packet.bytes.length; retainedChunks--; } }
            }
        } finally {
            synchronized(lock) {
                scheduled=false;
                if (!closed && (failure!=null || !pending.isEmpty())) scheduleLocked();
            }
        }
    }
    private void clearPending() {
        while (!pending.isEmpty()) { Packet packet=pending.removeFirst(); wipe(packet.bytes); retainedBytes-=packet.bytes.length; retainedChunks--; }
    }
    @Override public void close() { synchronized(lock) {
        closed=true; active=false; open=false; failed=false; attempt++; epoch++; failure=null; clearPending();
    } }
    public int retainedBytes() { synchronized(lock) { return retainedBytes; } }
    public int retainedChunks() { synchronized(lock) { return retainedChunks; } }
    private static void wipe(byte[] bytes) { Arrays.fill(bytes,(byte)0); }
}
