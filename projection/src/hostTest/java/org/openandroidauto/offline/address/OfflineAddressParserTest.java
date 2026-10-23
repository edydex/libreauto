// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import org.openandroidauto.offline.OfflineAreaPolicy;
import static org.junit.Assert.*;
public class OfflineAddressParserTest {
    private final OfflineAreaPolicy.Bounds bounds=OfflineAreaPolicy.validate(47.41,-122.29,47.39,-122.32);
    private final String node="{\"type\":\"node\",\"id\":15,\"lat\":47.4,\"lon\":-122.3,\"tags\":{\"addr:housenumber\":\"22975\",\"addr:street\":\"24th Avenue South\"}}";
    private String full(String elements,int total) { return "{\"osm3s\":{\"timestamp_osm_base\":\"2026-10-19T12:00:00Z\"},\"elements\":["+elements+",{\"type\":\"count\",\"id\":0,\"tags\":{\"total\":\""+total+"\"}}]}"; }
    private List<OfflineAddressEntry> parse(String json) throws IOException {
        List<OfflineAddressEntry> values=new ArrayList<>();
        OfflineAddressParser.read(new OfflineAddressInput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))),bounds,null,values::add,null);return values;
    }
    @Test public void bodyOutputImportsExactNodeAddressCoordinates() throws Exception {
        OfflineAddressEntry entry=parse(full(node,1)).get(0);
        assertEquals("node/15",entry.osmKey);assertEquals(47.4,entry.latitude,0);assertFalse(entry.approximate);
        assertEquals("22975 24th Avenue South",entry.name);
    }
    @Test public void wayBoundingCenterIsMarkedApproximateAndRefsAreSkipped() throws Exception {
        String way="{\"type\":\"way\",\"id\":2,\"nodes\":[1,2,3,4],\"center\":{\"lat\":47.4,\"lon\":-122.3},\"tags\":{\"name\":\"Main Street\",\"highway\":\"residential\"}}";
        OfflineAddressEntry entry=parse(full(way,1)).get(0);assertTrue(entry.approximate);assertEquals("street",entry.kind);
    }
    @Test public void centersOutsideTheSelectedAreaAreNotInventedOrClamped() throws Exception {
        String way="{\"type\":\"way\",\"id\":2,\"center\":{\"lat\":48,\"lon\":-122.3},\"tags\":{\"name\":\"Long Road\",\"highway\":\"primary\"}}";
        assertTrue(parse(full(way,1)).isEmpty());
    }
    @Test public void serverRemarksAndMissingOrMismatchedTerminalCountsNeverComplete() {
        assertThrows(IOException.class,()->parse("{\"remark\":\"timeout\",\"elements\":[]}"));
        assertThrows(IOException.class,()->parse("{\"elements\":["+node+"]}"));
        assertThrows(IOException.class,()->parse(full(node,2)));
        assertThrows(IOException.class,()->parse(full(node,1).substring(0,30)));
    }
    @Test public void trailingGarbageAndInvalidUtf8FailRatherThanCommitPartialRows() {
        assertThrows(IOException.class,()->parse(full(node,1)+"garbage"));
        byte[] bytes={(byte)'{',(byte)'"',(byte)0xff,(byte)'"',(byte)':',(byte)'1',(byte)'}'};
        assertThrows(IOException.class,()->OfflineAddressParser.read(new OfflineAddressInput(new ByteArrayInputStream(bytes)),bounds,null,value->{},null));
    }
    @Test public void totalBytesIndividualTokensAndNestingAreBoundedBeforeAllocation() {
        assertThrows(IOException.class,()->new OfflineAddressInput(new ByteArrayInputStream("123456".getBytes()),5).readAllBytes());
        assertThrows(IOException.class,()->new OfflineAddressInput(new ByteArrayInputStream(("\""+"x".repeat(8193)+"\"").getBytes())).readAllBytes());
        assertThrows(IOException.class,()->new OfflineAddressInput(new ByteArrayInputStream("[".repeat(33).getBytes())).readAllBytes());
    }
    @Test public void bracketCharactersInsideEscapedStringsDoNotChangeNesting() throws Exception {
        String content="[ \" { } ] \\";
        String escaped=content.replace("\\","\\\\").replace("\"","\\\"");
        String text="{\"ignored\":\""+escaped+"\",\"elements\":[{\"type\":\"count\",\"tags\":{\"total\":\"0\"}}]}";
        assertTrue(parse(text).isEmpty());
    }
    @Test public void interruptCancelsTheImportBeforeAnyRowsAreAccepted() {
        Thread.currentThread().interrupt();try { assertThrows(IOException.class,()->parse(full(node,1))); }finally { Thread.interrupted(); }
    }
}
