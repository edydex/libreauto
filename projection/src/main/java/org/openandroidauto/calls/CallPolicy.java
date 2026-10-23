// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.calls;

import java.text.Normalizer;
import java.util.Locale;

/** Only ordinary contact telephone numbers; never USSD, service codes, post-dial or URI input. */
public final class CallPolicy {
    public static final int MAX_QUERY = 80, MAX_RESULTS = 80;
    private CallPolicy() {}

    public static String dialNumber(String value) {
        if (value == null || value.length() > 80) return null;
        StringBuilder result = new StringBuilder();
        int digits=0;
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (c>='0' && c<='9') { result.append(c); digits++; }
            else if (c=='+' && result.length()==0 && digits==0) result.append(c);
            else if (c!=' ' && c!='-' && c!='(' && c!=')' && c!='.') return null;
        }
        // Short/emergency/service dialing belongs to the phone's normal dialer.
        return digits>=7 && digits<=15 ? result.toString() : null;
    }

    public static String searchQuery(String value) {
        if (value == null || value.length() > MAX_QUERY) return null;
        for (int i=0;i<value.length();i++) if (Character.isISOControl(value.charAt(i))) return null;
        return value.trim().replaceAll(" +", " ");
    }

    private static String folded(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public static boolean matches(CallContact contact, String query) {
        String safe=searchQuery(query); if (contact==null || safe==null) return false;
        if (safe.isEmpty()) return true;
        String name=folded(contact.name);
        boolean all=true;
        for (String part:folded(safe).split(" +")) if (!name.contains(part)) { all=false; break; }
        if (all) return true;
        String numeric=safe.replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
        return numeric.matches("\\+?[0-9]{2,15}") && contact.dialNumber.contains(numeric);
    }
}
