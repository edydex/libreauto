// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.voice;
/** One immutable reply owner per add-on turn. No notification text or mutable panel state. */
public final class DictationResultGate {
    private final long replyToken;
    private boolean readySeen, inputClosed, cancelled, delivered, remoteStartClaimed;
    public DictationResultGate(long replyToken) {
        if(replyToken==0) throw new IllegalArgumentException("Reply token required");
        this.replyToken=replyToken;
    }
    /** One atomic reservation: cancellation before this point prevents any remote START. */
    public synchronized boolean claimRemoteStart() {
        if(cancelled || remoteStartClaimed) return false;
        remoteStartClaimed=true;return true;
    }
    public synchronized void ready() { if(!cancelled && !inputClosed) readySeen=true; }
    public synchronized void inputClosed() { inputClosed=true; }
    /** A packet already admitted before natural endpointing is consumed without retaining audio. */
    public synchronized boolean canDiscardEndedInput() { return readySeen && inputClosed && !cancelled; }
    public synchronized void cancel() { cancelled=true; inputClosed=true; }
    /** Zero refuses an unready, live-input, duplicate or cancelled result. */
    public synchronized long claimTranscript() {
        if(cancelled || !readySeen || !inputClosed || delivered) return 0;
        delivered=true;return replyToken;
    }
}
