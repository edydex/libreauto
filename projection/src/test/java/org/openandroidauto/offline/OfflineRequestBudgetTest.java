// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OfflineRequestBudgetTest {
    private static final class Store implements OfflineRequestBudget.Backend {
        long count;boolean writable=true;
        public long used() { return count; }
        public boolean store(long value) { if(!writable)return false;count=value;return true; }
    }
    @Test public void resumedDownloadKeepsItsPreviousReservations() {
        Store store=new Store();store.count=OfflineRequestBudget.MAX_REQUESTS-1;
        assertTrue(new OfflineRequestBudget(store).reserve());
        assertFalse(new OfflineRequestBudget(store).reserve());
        assertEquals(OfflineRequestBudget.MAX_REQUESTS,store.count);
    }
    @Test public void refusalToPersistPreventsTheRequestFromStarting() {
        Store store=new Store();store.writable=false;
        assertFalse(new OfflineRequestBudget(store).reserve());assertEquals(0,store.count);
    }
    @Test public void corruptCountersAndBackendFailuresFailClosed() {
        for(long count:new long[]{-1,OfflineRequestBudget.MAX_REQUESTS,Long.MAX_VALUE}) {
            Store store=new Store();store.count=count;assertFalse(new OfflineRequestBudget(store).reserve());
        }
        assertFalse(new OfflineRequestBudget(new OfflineRequestBudget.Backend() {
            public long used() { throw new IllegalStateException(); }
            public boolean store(long value) { fail("A corrupt read cannot reserve a request");return true; }
        }).reserve());
    }
    @Test public void fourConcurrentWorkersCannotExceedThePersistentAllowance() throws Exception {
        Store store=new Store();store.count=OfflineRequestBudget.MAX_REQUESTS-3;
        OfflineRequestBudget budget=new OfflineRequestBudget(store);AtomicInteger started=new AtomicInteger();
        Thread[] workers=new Thread[4];
        for(int index=0;index<workers.length;index++) {
            workers[index]=new Thread(()->{for(int n=0;n<10;n++)if(budget.reserve())started.incrementAndGet();});workers[index].start();
        }
        for(Thread worker:workers)worker.join();
        assertEquals(3,started.get());assertEquals(OfflineRequestBudget.MAX_REQUESTS,store.count);
    }
}
