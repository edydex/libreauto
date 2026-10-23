// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
/** Host test bridge emits the actual production SQL/query expressions; not packaged. */
public final class OfflineAddressContractDump {
    public static void main(String[] args) {
        if(args.length>0&&args[0].equals("query")) {
            OfflineAddressText.Query q=OfflineAddressText.query(args[1]);line(q==null?"":q.match);line(q==null||q.houseToken==null?"":q.houseToken);return;
        }
        for(String sql:OfflineAddressSchema.CREATE)line(sql);
        line(OfflineAddressSchema.SEARCH);line(OfflineAddressSchema.DELETE_ORPHANS);
    }
    private static void line(String value) { System.out.println(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))); }
}
