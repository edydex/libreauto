// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import java.util.concurrent.atomic.AtomicLong;

/** In-memory place-search correlation. Dictation can only produce candidates, never a route action. */
public final class NavigationSearchGate {
    public static final class Candidates {
        public final long revision;
        public final boolean requireConfirmation;
        private Candidates(long revision, boolean requireConfirmation) { this.revision=revision; this.requireConfirmation=requireConfirmation; }
    }
    public static final class Confirmation {
        public final String name;
        public final double latitude, longitude;
        private final Candidates candidates;
        private Confirmation(Candidates candidates, String name, double latitude, double longitude) {
            this.candidates=candidates; this.name=name; this.latitude=latitude; this.longitude=longitude;
        }
    }
    private static final AtomicLong sequence=new AtomicLong();
    private long revision, dictation;
    private Candidates current;
    private Confirmation confirmation;

    public static String searchQuery(String text) {
        if(text==null || text.length()>200)return null;
        for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i)))return null;
        String result=text.strip(); return result.length()>=2 ? result : null;
    }

    public synchronized void invalidate() { revision++; dictation=0; current=null; confirmation=null; }
    public synchronized long prepareDictation() {
        invalidate(); do { dictation=sequence.incrementAndGet(); } while(dictation==0); return dictation;
    }
    public synchronized long pendingDictation() { return dictation; }
    public synchronized boolean hasCommandFlow() { return current!=null && current.requireConfirmation; }
    public synchronized boolean hasCancelableFlow() { return dictation!=0 || hasCommandFlow(); }
    public synchronized boolean consumeDictation(long token) {
        if(token==0 || dictation!=token)return false; dictation=0; return true;
    }
    public synchronized void cancelDictation(Long token) { if(token==null || token==dictation)dictation=0; }
    public synchronized Candidates beginCandidates(boolean requireConfirmation) {
        invalidate(); current=new Candidates(revision,requireConfirmation); return current;
    }
    public synchronized boolean current(Candidates candidates) { return candidates!=null && current==candidates && candidates.revision==revision; }
    public synchronized Confirmation confirm(Candidates candidates, String name, double latitude, double longitude) {
        if(!current(candidates) || !candidates.requireConfirmation || name==null || name.isBlank() || name.length()>1000
                || !Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude)>90 || Math.abs(longitude)>180)return null;
        confirmation=new Confirmation(candidates,name,latitude,longitude); return confirmation;
    }
    public synchronized boolean consume(Confirmation selected) {
        if(selected==null || confirmation!=selected || !current(selected.candidates))return false;
        invalidate(); return true;
    }
}
