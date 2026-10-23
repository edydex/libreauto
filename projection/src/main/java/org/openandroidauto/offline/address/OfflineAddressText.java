// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Bounded literal FTS4 queries, with common English street abbreviations normalized symmetrically. */
public final class OfflineAddressText {
    private OfflineAddressText() {}
    public static String normalize(String text) {
        String plain=Normalizer.normalize(text==null?"":text,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
        StringBuilder out=new StringBuilder();
        for(String word:plain.replaceAll("[^\\p{L}\\p{N}]+"," ").trim().split(" +")) {
            String token=switch(word) {
                case "street" -> "st";case "avenue" -> "ave";case "road" -> "rd";case "boulevard" -> "blvd";
                case "drive" -> "dr";case "lane" -> "ln";case "court" -> "ct";case "place" -> "pl";
                case "parkway" -> "pkwy";case "highway" -> "hwy";case "terrace" -> "ter";
                case "north" -> "n";case "south" -> "s";case "east" -> "e";case "west" -> "w";
                case "northeast" -> "ne";case "northwest" -> "nw";case "southeast" -> "se";case "southwest" -> "sw";
                default -> word;
            };
            if(!token.isEmpty()) { if(out.length()>0)out.append(' ');out.append(token); }
        }
        return out.toString();
    }
    public static String houseTokens(String text) {
        StringBuilder out=new StringBuilder("|");
        for(String house:(text==null?"":text).split("[,;]")) {
            String token=house.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+","");
            if(!token.isEmpty()&&token.length()<=64)out.append(token.replace("|","")).append('|');
        }
        return out.toString();
    }
    public static final class Query {
        public final String match,houseToken,normalized;
        Query(String match,String houseToken,String normalized) { this.match=match;this.houseToken=houseToken;this.normalized=normalized; }
    }
    public static Query query(String input) {
        if(input==null||input.trim().isEmpty()||input.length()>240)return null;
        String text=input.trim();String first=text.split("\\s+",2)[0];
        String house=first.matches("(?i)\\d+[a-z]?(?:[-/]\\d+[a-z]?)?")?"|"+first.toLowerCase(Locale.ROOT)+"|":null;
        if(house!=null) {
            text=text.split(",",2)[0].replaceAll("(?i)(?:#|\\b(?:apt|unit|suite)\\.?\\s*)[\\p{L}\\p{N}-]+","");
        }
        String normalized=normalize(text);
        LinkedHashSet<String> unique=new LinkedHashSet<>(List.of(normalized.split(" +")));
        unique.remove("");if(unique.isEmpty()||unique.size()>16)return null;
        List<String> terms=new ArrayList<>();
        for(String token:unique) {
            if(token.length()>64)return null;
            // FTS4 requires the prefix star INSIDE the quoted token. Quotes/operators
            // from input never reach this expression: normalization emits letters/numbers.
            boolean prefix=token.length()>1&&!token.matches("\\d+");
            terms.add("\""+token+(prefix?"*":"")+"\"");
        }
        // Android builds may use the FTS4 standard parser, where an explicit AND
        // is an ordinary search word. Juxtaposition is conjunction in both the
        // standard and enhanced parsers; every user token stays quoted literally.
        return new Query(String.join(" ",terms),house,normalized);
    }
}
