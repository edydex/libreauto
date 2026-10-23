// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;
import java.util.Locale;
import org.junit.Test;
import org.openandroidauto.offline.OfflineAreaPolicy;
import static org.junit.Assert.*;
public class OfflineAddressQueryTest {
    @Test public void outputIncludesNodeCoordinatesAndAnIndependentUntruncatedCount() {
        String query=OfflineAddressDownloader.query(OfflineAreaPolicy.validate(47.406,-122.294,47.388,-122.320));
        assertTrue(query.endsWith("out body center qt 100001;out count;"));
        assertTrue(query.contains("nwr[\"addr:housenumber\"]"));
        assertTrue(query.contains("way[\"highway\"][\"name\"]"));
        assertTrue(query.contains("nwr[\"name\"][\"amenity\"]"));
        assertFalse(query.contains("out tags center"));
    }
    @Test public void boundsUseServerDecimalSyntaxRegardlessOfPhoneLocale() {
        Locale saved=Locale.getDefault();try {
            Locale.setDefault(Locale.GERMANY);
            assertTrue(OfflineAddressDownloader.query(OfflineAreaPolicy.validate(47.406,-122.294,47.388,-122.320)).contains("(47.3880000000,-122.3200000000,47.4060000000,-122.2940000000)"));
        } finally { Locale.setDefault(saved); }
    }
    @Test public void downloadQueryCannotExpandIntoUnboundedCoverage() {
        assertThrows(IllegalArgumentException.class,()->OfflineAddressDownloader.query(OfflineAreaPolicy.validate(48,-121,47,-123)));
        assertEquals("https://overpass.private.coffee/api/interpreter",OfflineAddressDownloader.DEFAULT_ENDPOINT);
    }
}
