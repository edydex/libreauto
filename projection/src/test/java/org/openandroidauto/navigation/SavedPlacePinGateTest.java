// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;
import org.junit.Test;
import static org.junit.Assert.*;

public class SavedPlacePinGateTest {
    @Test public void noCandidateBecomesUsableUntilItsExplicitCurrentConfirmation() {
        SavedPlacePinGate gate=new SavedPlacePinGate();gate.setAddress("123 Example Ave");
        SavedPlacePinGate.Review first=gate.review(new GeoPoint(10,20));assertNull(gate.confirmedPoint());
        assertTrue(gate.confirm(first));assertEquals(10,gate.confirmedPoint().latitude,0);assertFalse(gate.confirm(first));
    }
    @Test public void addressChangesAndNewPinReviewsRejectOldCandidateConfirmations() {
        SavedPlacePinGate gate=new SavedPlacePinGate();gate.setAddress("First address");
        SavedPlacePinGate.Review old=gate.review(new GeoPoint(10,20));gate.setAddress("Second address");
        assertFalse(gate.confirm(old));assertNull(gate.confirmedPoint());
        SavedPlacePinGate.Review a=gate.review(new GeoPoint(11,21)),b=gate.review(new GeoPoint(12,22));
        assertFalse(gate.confirm(a));assertTrue(gate.confirm(b));assertEquals(12,gate.confirmedPoint().latitude,0);
    }
    @Test public void foreignAndCancelledReviewsCannotPublishPinsAndExistingAddressEditInvalidatesPin() {
        SavedPlacePinGate a=new SavedPlacePinGate(),b=new SavedPlacePinGate();
        SavedPlacePinGate.Review foreign=b.review(new GeoPoint(10,20));assertFalse(a.confirm(foreign));
        a.existing("Stored address",new GeoPoint(10,20));assertNotNull(a.confirmedPoint());
        a.setAddress("Stored address");assertNotNull(a.confirmedPoint());
        a.setAddress("Changed address");assertNull(a.confirmedPoint());
        SavedPlacePinGate.Review pending=a.review(new GeoPoint(30,40));a.invalidate();assertFalse(a.confirm(pending));
    }
}
