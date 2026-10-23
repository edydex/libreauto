// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.openandroidauto.offline.OfflineAreaPolicy;
/** Parses the explicitly fetched public church fixture with production importer logic. */
public final class OfflineAddressPrototypeMain {
    public static void main(String[] args) throws Exception {
        int[] counts={0,0};long started=System.nanoTime();
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])))) {
            OfflineAddressParser.Parsed parsed=OfflineAddressParser.read(new OfflineAddressInput(new FileInputStream(args[0])),
                    OfflineAreaPolicy.validate(47.406,-122.294,47.388,-122.320),null,entry->{
                        counts[0]++;if(entry.approximate)counts[1]++;
                        string(out,entry.osmKey);string(out,entry.name);out.writeDouble(entry.latitude);out.writeDouble(entry.longitude);out.writeBoolean(entry.approximate);
                        string(out,entry.kind);string(out,entry.houseTokens);string(out,entry.searchText);
                    },null);
            System.out.println("PUBLIC FIXTURE ACTUAL ANDROID JSONREADER: entries="+counts[0]+", approximate="+counts[1]+", timestamp="+parsed.timestamp+", seconds="+(System.nanoTime()-started)/1e9);
        }
    }
    private static void string(DataOutputStream out,String value) throws IOException { byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes); }
}
