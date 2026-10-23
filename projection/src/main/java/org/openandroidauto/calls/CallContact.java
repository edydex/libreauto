// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.calls;

import java.util.Objects;

/** One selected phone-number row. Never persisted by this app. */
public final class CallContact {
    public final long dataId, contactId;
    public final String lookupKey, name, label, number, dialNumber;

    public CallContact(long dataId, long contactId, String lookupKey, String name, String label, String number) {
        if (dataId <= 0 || contactId <= 0 || !bounded(lookupKey, 1024) || !bounded(name, 200)
                || !bounded(label, 80) || !bounded(number, 80)) throw new IllegalArgumentException("Invalid contact row");
        String dial = CallPolicy.dialNumber(number);
        if (dial == null) throw new IllegalArgumentException("Unsupported telephone number");
        this.dataId=dataId; this.contactId=contactId; this.lookupKey=lookupKey;
        this.name=name; this.label=label; this.number=number; this.dialNumber=dial;
    }

    private static boolean bounded(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) return false;
        for (int i=0;i<value.length();i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }

    /** Every confirmation field must still match the provider, including the row's contact identity. */
    public boolean sameTarget(CallContact other) {
        return other != null && dataId == other.dataId && contactId == other.contactId
            && lookupKey.equals(other.lookupKey) && name.equals(other.name) && label.equals(other.label)
            && number.equals(other.number) && dialNumber.equals(other.dialNumber);
    }

    @Override public boolean equals(Object value) { return value instanceof CallContact && sameTarget((CallContact)value); }
    @Override public int hashCode() { return Objects.hash(dataId,contactId,lookupKey,name,label,number); }
}
