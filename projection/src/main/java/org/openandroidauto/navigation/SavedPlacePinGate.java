// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

/** A pin is usable only after review for the current address. No resolver result auto-saves. */
public final class SavedPlacePinGate {
    public static final class Review {
        public final GeoPoint point;
        private final SavedPlacePinGate owner;
        private final long revision;
        private Review(SavedPlacePinGate owner,long revision,GeoPoint point) { this.owner=owner;this.revision=revision;this.point=point; }
    }
    private String address="";
    private long revision=1;
    private Review reviewing;
    private GeoPoint confirmed;
    public void setAddress(String value) {
        String next=value==null?"":value.strip();
        if(!address.equals(next)) { address=next;invalidate(); }
    }
    public void invalidate() { revision++;reviewing=null;confirmed=null; }
    public Review review(GeoPoint point) {
        if(point==null)throw new IllegalArgumentException("Choose a map pin");
        invalidate();reviewing=new Review(this,revision,point);return reviewing;
    }
    public boolean confirm(Review review) {
        if(review==null||review.owner!=this||reviewing!=review||review.revision!=revision)return false;
        confirmed=review.point;reviewing=null;return true;
    }
    /** Existing saved/car-selected coordinates already have explicit selection provenance. */
    public void existing(String address,GeoPoint point) { setAddress(address);Review review=review(point);confirm(review); }
    public GeoPoint confirmedPoint() { return confirmed; }
}
