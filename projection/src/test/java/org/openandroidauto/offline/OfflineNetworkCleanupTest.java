// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OfflineNetworkCleanupTest {
    @Test public void pauseAndCloseNeverTearDownSocketsOnTheCallerAndWorkerExits() throws Exception {
        Thread caller=Thread.currentThread();List<String> order=Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean wrongThread=new AtomicBoolean();
        OfflineMapHttp.NetworkCleanup cleanup=new OfflineMapHttp.NetworkCleanup(()->{
            wrongThread.compareAndSet(false,Thread.currentThread()==caller);order.add("cancel");
        },()->{
            wrongThread.compareAndSet(false,Thread.currentThread()==caller);order.add("finish");
        });
        cleanup.cancel();cleanup.close();
        assertTrue(cleanup.awaitTermination(2,TimeUnit.SECONDS));
        assertFalse(wrongThread.get());
        assertEquals(List.of("cancel","cancel","finish"),order);
    }
    @Test public void racingPauseAndRepeatedCloseKeepOneFinalCleanupAndRejectNoTasks() throws Exception {
        AtomicInteger finishes=new AtomicInteger(),failures=new AtomicInteger();
        List<String> order=Collections.synchronizedList(new ArrayList<>());
        OfflineMapHttp.NetworkCleanup cleanup=new OfflineMapHttp.NetworkCleanup(()->order.add("cancel"),()->{order.add("finish");finishes.incrementAndGet();});
        Thread[] callers=new Thread[4];
        for(int index=0;index<callers.length;index++) {
            final boolean closing=index==3;
            callers[index]=new Thread(()->{try{for(int n=0;n<100;n++)if(closing)cleanup.close();else cleanup.cancel();}catch(RuntimeException error){failures.incrementAndGet();}});
            callers[index].start();
        }
        for(Thread caller:callers)caller.join();cleanup.close();
        assertTrue(cleanup.awaitTermination(2,TimeUnit.SECONDS));
        assertEquals(0,failures.get());assertEquals(1,finishes.get());
        assertEquals("finish",order.get(order.size()-1));
    }
    @Test public void cancellationFailureStillRunsFinalTeardownAndTerminates() throws Exception {
        AtomicInteger finishes=new AtomicInteger();
        OfflineMapHttp.NetworkCleanup cleanup=new OfflineMapHttp.NetworkCleanup(()->{throw new IllegalStateException("Synthetic socket close failure");},finishes::incrementAndGet);
        cleanup.cancel();cleanup.close();
        assertTrue(cleanup.awaitTermination(2,TimeUnit.SECONDS));assertEquals(1,finishes.get());
    }
}
