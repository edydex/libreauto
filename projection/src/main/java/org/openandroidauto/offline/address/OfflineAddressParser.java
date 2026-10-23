// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;

import android.os.CancellationSignal;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.openandroidauto.offline.OfflineAreaPolicy;

/** Streaming Overpass JSON. Any server remark, partial/count mismatch or cancel aborts the import. */
final class OfflineAddressParser {
    static final int MAX_ELEMENTS=100_000;
    private static final Set<String> TAGS=Set.of("name","name:en","official_name","alt_name","short_name","addr:housenumber","addr:street","addr:place",
            "addr:city","addr:state","addr:postcode","addr:country","addr:unit","addr:housename","addr:full","highway","amenity","shop","tourism","leisure","office","healthcare","place","total");
    interface Sink { void entry(OfflineAddressEntry entry) throws IOException; }
    static final class Parsed {
        final int entries;final String timestamp;
        Parsed(int entries,String timestamp) { this.entries=entries;this.timestamp=timestamp; }
    }
    static Parsed read(OfflineAddressInput input,OfflineAreaPolicy.Bounds bounds,CancellationSignal cancel,Sink sink,OfflineAddressDownloader.Progress progress) throws IOException {
        JsonReader reader=new JsonReader(new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)));
        reader.setLenient(false);int raw=0,indexed=0;long declared=-1;boolean elements=false;String timestamp="";
        try {
            reader.beginObject();
            while(reader.hasNext()) {
                active(cancel);String key=reader.nextName();
                if(key.equals("remark"))throw new IOException("Address provider returned an incomplete result; try again later");
                if(key.equals("osm3s")) {
                    reader.beginObject();while(reader.hasNext()) { if(reader.nextName().equals("timestamp_osm_base"))timestamp=reader.nextString();else reader.skipValue(); }reader.endObject();
                } else if(key.equals("elements")) {
                    if(elements)throw new IOException("Duplicate address result array");elements=true;reader.beginArray();
                    while(reader.hasNext()) {
                        active(cancel);Element element=element(reader);
                        if(element.type.equals("count")) {
                            if(declared>=0)throw new IOException("Duplicate address result count");
                            try { declared=Long.parseLong(element.tags.getOrDefault("total","-1")); }
                            catch(NumberFormatException error) { throw new IOException("Invalid address result count"); }
                        } else {
                            if(++raw>MAX_ELEMENTS)throw new IOException("Too many mapped places; choose a smaller area");
                            OfflineAddressEntry entry=OfflineAddressEntry.from(element.type,element.id,element.latitude,element.longitude,element.tags,bounds);
                            if(entry!=null) { sink.entry(entry);indexed++; }
                        }
                        if(progress!=null&&raw%100==0)progress.onProgress(input.bytesRead(),indexed);
                    }
                    reader.endArray();
                } else reader.skipValue();
            }
            reader.endObject();
            if(reader.peek()!=JsonToken.END_DOCUMENT||!elements||declared!=raw)throw new IOException("Incomplete address data; previous downloads were kept");
            active(cancel);if(progress!=null)progress.onProgress(input.bytesRead(),indexed);
            return new Parsed(indexed,timestamp.length()<=80?timestamp:"");
        } catch(IllegalStateException|NumberFormatException error) { throw new IOException("Malformed address data; previous downloads were kept",error); }
        finally { reader.close(); }
    }
    static void active(CancellationSignal cancel) throws IOException {
        if(Thread.currentThread().isInterrupted()||(cancel!=null&&cancel.isCanceled()))throw new IOException("Address download cancelled");
    }
    private static final class Element {
        String type="";long id;double latitude=Double.NaN,longitude=Double.NaN;Map<String,String> tags=new HashMap<>();
    }
    private static Element element(JsonReader reader) throws IOException {
        Element value=new Element();reader.beginObject();
        while(reader.hasNext()) {
            String key=reader.nextName();
            switch(key) {
                case "type" -> value.type=reader.nextString();
                case "id" -> value.id=reader.nextLong();
                case "lat" -> value.latitude=reader.nextDouble();
                case "lon" -> value.longitude=reader.nextDouble();
                case "center" -> {
                    reader.beginObject();while(reader.hasNext()) {
                        switch(reader.nextName()) { case "lat" -> value.latitude=reader.nextDouble();case "lon" -> value.longitude=reader.nextDouble();default -> reader.skipValue(); }
                    }reader.endObject();
                }
                case "tags" -> {
                    reader.beginObject();int count=0;while(reader.hasNext()) {
                        if(++count>512)throw new IOException("Address feature has too many tags");
                        String tag=reader.nextName();
                        if(TAGS.contains(tag)) {
                            String text=reader.nextString();
                            if(text.length()>1024)throw new IOException("Address feature has an oversized name");
                            value.tags.put(tag,text.replaceAll("[\\p{Cntrl}]+"," ").trim());
                        } else reader.skipValue();
                    }reader.endObject();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();return value;
    }
}
