package org.openandroidauto.offline;
import org.junit.Test;
import static org.junit.Assert.*;
public class OfflineGraphDownloadPolicyTest {
    @Test public void completeAndResumedLengthAreBounded() {
        assertEquals(64000000,OfflineGraphDownloadPolicy.totalBytes(200,0,64000000,null));
        assertEquals(64000000,OfflineGraphDownloadPolicy.totalBytes(206,1000,63999000,"bytes 1000-63999999/64000000"));
    }
    @Test public void mismatchedRangeCannotAppendDifferentBytes() {
        assertThrows(IllegalArgumentException.class,()->OfflineGraphDownloadPolicy.totalBytes(206,1000,63000,"bytes 0-63999/64000"));
        assertThrows(IllegalArgumentException.class,()->OfflineGraphDownloadPolicy.totalBytes(206,1000,63000,"bytes 1000-62000/64000"));
    }
    @Test public void unknownOversizeAndErrorResponsesRejected() {
        assertThrows(IllegalArgumentException.class,()->OfflineGraphDownloadPolicy.totalBytes(200,0,-1,null));
        assertThrows(IllegalArgumentException.class,()->OfflineGraphDownloadPolicy.totalBytes(200,0,250000001,null));
        assertThrows(IllegalArgumentException.class,()->OfflineGraphDownloadPolicy.totalBytes(404,0,10000,null));
    }
}
