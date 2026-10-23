// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.voice;

import java.util.Locale;

/** Bounded local grammar. Results authorize choices only, never dial, route or send. */
public final class OfflineCommand {
    public enum Kind { CALL, DIRECTIONS, MESSAGE, REPLY, CANCEL, UNKNOWN }
    public final Kind kind;
    public final String query;
    private OfflineCommand(Kind kind, String query) { this.kind=kind; this.query=query; }
    public static OfflineCommand parse(String transcript) {
        if (transcript==null || transcript.length()>240) return unknown();
        for (int i=0;i<transcript.length();i++) if (Character.isISOControl(transcript.charAt(i))) return unknown();
        String value=transcript.trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) return unknown();
        for (int i=0;i<value.length();i++) if (Character.isISOControl(value.charAt(i))) return unknown();
        String folded=value.toLowerCase(Locale.ROOT);
        if (folded.equals("cancel") || folded.equals("never mind")) return new OfflineCommand(Kind.CANCEL, "");
        String[] prefixes={"directions to ","navigate to ","reply to ","message ","text ","call "};
        Kind[] kinds={Kind.DIRECTIONS,Kind.DIRECTIONS,Kind.REPLY,Kind.MESSAGE,Kind.MESSAGE,Kind.CALL};
        for(int i=0;i<prefixes.length;i++) if(folded.startsWith(prefixes[i])) {
            String query=value.substring(prefixes[i].length()).trim();
            if(query.isEmpty() || query.length()>120) return unknown();
            return new OfflineCommand(kinds[i],query);
        }
        return unknown();
    }
    private static OfflineCommand unknown() { return new OfflineCommand(Kind.UNKNOWN, ""); }
}
