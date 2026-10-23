// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;
import org.junit.Test;
import static org.junit.Assert.*;
public class OfflineAddressTextTest {
    @Test public void commonStreetAbbreviationsMatchSymmetrically() {
        assertEquals(OfflineAddressText.normalize("22975 24th Avenue South"),OfflineAddressText.normalize("22975 24th Ave S"));
        assertEquals("cafe n main rd",OfflineAddressText.normalize("Café North Main Road"));
    }
    @Test public void literalFts4TermsCannotBecomeOperatorsOrColumnExpressions() {
        OfflineAddressText.Query q=OfflineAddressText.query("name:Main OR \"Church\"*");
        assertEquals("\"name*\" \"main*\" \"or*\" \"church*\"",q.match);
        assertFalse(q.match.contains("name:"));
    }
    @Test public void addressMatchesExactHouseNumberAndStripsUnmappedUnitAndTrailingCity() {
        OfflineAddressText.Query q=OfflineAddressText.query("22975 24th Ave S #7109, Des Moines, WA 98198");
        assertEquals("|22975|",q.houseToken);
        assertEquals("\"22975\" \"24th*\" \"ave*\" \"s\"",q.match);
        assertEquals("22975 24th ave s",q.normalized);
    }
    @Test public void numberedStreetIsNotMistakenForAHouseNumber() {
        assertNull(OfflineAddressText.query("24th Avenue South").houseToken);
        assertEquals("|12b|",OfflineAddressText.query("12B Main Street").houseToken);
        assertEquals("|7-9|",OfflineAddressText.query("7-9 Main Street").houseToken);
    }
    @Test public void houseMembershipNeverInventsAnInterpolatedNumber() {
        assertEquals("|12b|12c|",OfflineAddressText.houseTokens("12B, 12C"));
        assertFalse(OfflineAddressText.houseTokens("12B, 12C").contains("|12|"));
        assertFalse(OfflineAddressText.houseTokens("7-9").contains("|8|"));
    }
    @Test public void emptyAndOverlongQueriesAreRejectedBeforeDiskSearch() {
        assertNull(OfflineAddressText.query("!?"));assertNull(OfflineAddressText.query(null));
        assertNull(OfflineAddressText.query("x".repeat(241)));
        assertNull(OfflineAddressText.query("a b c d e f g h i j k l m n o p q"));
    }
    @Test public void portableConjunctionUsesNoParserSpecificOperatorsAndKeepsUserWordsLiteral() {
        OfflineAddressText.Query query=OfflineAddressText.query("Main AND First");
        assertEquals("\"main*\" \"and*\" \"first*\"",query.match);
        assertFalse(query.match.contains(" AND "));
        assertEquals("\"main*\" \"first*\"",OfflineAddressText.query("Main First").match);
        assertEquals("\"and*\" \"or*\" \"not*\"",OfflineAddressText.query("AND OR NOT").match);
    }
}
