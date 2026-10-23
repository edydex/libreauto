// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.voice;

import java.util.concurrent.atomic.AtomicLong;

/** One immutable purpose/target/generation per asynchronous dictation request.
 * Tokens correlate results only; no contact, recipient, number or transcript is stored.
 */
public final class DictationRouteGate {
    public enum Purpose { MESSAGE_REPLY, CONTACT_SEARCH, MAP_PLACE_SEARCH, COMMAND }
    public static final class Request {
        public final long token, targetToken, generation;
        public final Purpose purpose;
        private Request(long token,long targetToken,long generation,Purpose purpose) {
            this.token=token;this.targetToken=targetToken;this.generation=generation;this.purpose=purpose;
        }
    }
    private static final AtomicLong SEQUENCE=new AtomicLong();
    private long generation;
    private Request current;
    private boolean delivered;

    /** Refuse replacement while the dashboard still owns an older turn. */
    public synchronized Request begin(Purpose purpose,long targetToken) {
        if(purpose==null || targetToken==0 || current!=null) return null;
        long token;do {token=SEQUENCE.incrementAndGet();}while(token==0);
        current=new Request(token,targetToken,++generation,purpose);delivered=false;return current;
    }
    public synchronized Request current() { return current; }
    public synchronized boolean owns(Purpose purpose,long targetToken) {
        return current!=null && current.purpose==purpose && current.targetToken==targetToken;
    }
    public synchronized boolean canReportStatus() { return current!=null && !delivered; }
    /** Routing requires the same visible purpose as well as the original request token. */
    public synchronized Request consume(long token,Purpose visiblePurpose) {
        if(current==null || delivered || current.token!=token || current.generation!=generation || current.purpose!=visiblePurpose) return null;
        delivered=true;return current;
    }
    /** Every tab/target/stop invalidation advances the generation before any panel cancellation callback. */
    public synchronized Request cancel() {
        Request old=current;current=null;delivered=false;generation++;return old;
    }
}
