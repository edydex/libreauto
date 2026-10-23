// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.calls;

import java.util.concurrent.atomic.AtomicLong;

/** Query and dictation correlation only; no contact or transcript storage. */
public final class CallSearchState {
    private static final AtomicLong tokens=new AtomicLong();
    private long generation, dictationToken, dictationGeneration;
    public synchronized long changed() { generation++; dictationToken=0; return generation; }
    public synchronized long generation() { return generation; }
    public synchronized boolean current(long queryGeneration) { return queryGeneration==generation; }
    public synchronized long prepareDictation() {
        long next=tokens.incrementAndGet(); if(next==0)next=tokens.incrementAndGet();
        dictationToken=next; dictationGeneration=generation; return next;
    }
    public synchronized boolean consumeDictation(long token) {
        if(token==0 || token!=dictationToken || dictationGeneration!=generation)return false;
        dictationToken=0;return true;
    }
    public synchronized long pendingDictation() { return dictationToken; }
    public synchronized void cancelDictation(Long token) { if(token==null || token==dictationToken)dictationToken=0; }
}
