// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.channel;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CarPcmIngressTest {
    private static final class Main implements CarPcmIngress.Scheduler {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        int posts, maxPending; boolean accept=true;
        public synchronized boolean post(Runnable r) {
            posts++; if (!accept) return false;
            tasks.addLast(r); maxPending=Math.max(maxPending,tasks.size()); return true;
        }
        synchronized Runnable take() { return tasks.pollFirst(); }
        synchronized int size() { return tasks.size(); }
        void one() { Runnable r=take(); assertNotNull(r); r.run(); }
        void all() { for (int i=0;size()>0;i++) { assertTrue("drain did not settle",i<100); one(); } }
    }
    private static byte[] pcm(int size) { byte[] p=new byte[size]; Arrays.fill(p,(byte)7); return p; }
    private static void zero(byte[] p) { assertArrayEquals(new byte[p.length],p); }
    private static long open(CarPcmIngress q) { q.beginAttempt();q.setOpen(true);return q.openEpoch(); }

    @Test public void stalledMainHasOnePostAndHardAggregateCapThenOneFailure() {
        Main main=new Main();List<CarPcmIngress.Failure> failures=new ArrayList<>();
        CarPcmIngress q=new CarPcmIngress(main,(p,r)->fail("overflow must detach pending"),(a,r)->failures.add(r));
        long epoch=open(q);List<byte[]> all=new ArrayList<>();
        for(int i=0;i<100;i++) { byte[] p=pcm(640);all.add(p);
            CarPcmIngress.Offer result=q.offerOwned(epoch,p,16000);
            assertEquals(i<10?CarPcmIngress.Offer.ACCEPTED:i==10?CarPcmIngress.Offer.FAILED:CarPcmIngress.Offer.IGNORED,result);
            assertTrue(q.retainedBytes()<=6400);assertTrue(q.retainedChunks()<=10);
        }
        assertEquals(1,main.posts);assertEquals(1,main.size());assertEquals(0,q.retainedBytes());
        for(byte[] p:all)zero(p);main.all();
        assertEquals(Collections.singletonList(CarPcmIngress.Failure.OVERFLOW),failures);assertEquals(1,main.posts);
    }
    @Test public void byteLimitIsIndependentOfChunkLimit() {
        Main main=new Main();List<CarPcmIngress.Failure> failures=new ArrayList<>();
        CarPcmIngress q=new CarPcmIngress(main,(p,r)->fail(),(a,r)->failures.add(r));long epoch=open(q);
        byte[] max=pcm(6400), extra=pcm(2);assertEquals(CarPcmIngress.Offer.ACCEPTED,q.offerOwned(epoch,max,16000));
        assertEquals(CarPcmIngress.Offer.FAILED,q.offerOwned(epoch,extra,16000));zero(max);zero(extra);
        main.all();assertEquals(Collections.singletonList(CarPcmIngress.Failure.OVERFLOW),failures);
    }
    @Test public void chunkLimitIsIndependentOfByteLimit() {
        Main main=new Main();CarPcmIngress q=new CarPcmIngress(main,(p,r)->fail(),(a,r)->assertEquals(CarPcmIngress.Failure.OVERFLOW,r));
        long epoch=open(q);for(int i=0;i<10;i++)assertEquals(CarPcmIngress.Offer.ACCEPTED,q.offerOwned(epoch,pcm(2),16000));
        assertEquals(20,q.retainedBytes());assertEquals(CarPcmIngress.Offer.FAILED,q.offerOwned(epoch,pcm(2),16000));main.all();
    }
    @Test public void successfulSinkBorrowsThenOwnedInputIsWiped() {
        Main main=new Main();List<byte[]> copies=new ArrayList<>();byte[] p=pcm(6400);
        CarPcmIngress q=new CarPcmIngress(main,(borrowed,r)->{assertSame(p,borrowed);assertEquals(16000,r);copies.add(borrowed.clone());},(a,r)->fail());
        assertEquals(CarPcmIngress.Offer.ACCEPTED,q.offerOwned(open(q),p,16000));main.all();
        zero(p);assertEquals(7,copies.get(0)[0]);assertEquals(0,q.retainedBytes());assertEquals(0,q.retainedChunks());
    }
    @Test public void invalidAudioFailsOnlyCurrentOpenEpoch() {
        for(int[] invalid:new int[][]{{0,16000},{3,16000},{6402,16000},{640,48000}}) {
            Main main=new Main();List<CarPcmIngress.Failure> failures=new ArrayList<>();
            CarPcmIngress q=new CarPcmIngress(main,(p,r)->fail(),(a,r)->failures.add(r));long epoch=open(q);byte[] p=pcm(invalid[0]);
            assertEquals(CarPcmIngress.Offer.FAILED,q.offerOwned(epoch,p,invalid[1]));zero(p);main.all();
            assertEquals(Collections.singletonList(CarPcmIngress.Failure.INVALID_AUDIO),failures);
        }
        Main main=new Main();CarPcmIngress q=new CarPcmIngress(main,(p,r)->fail(),(a,r)->fail());
        long old=open(q);q.setOpen(false);q.setOpen(true);
        assertEquals(CarPcmIngress.Offer.IGNORED,q.invalidAudio(old));assertEquals(CarPcmIngress.Offer.IGNORED,q.offerOwned(old,null,16000));
        assertEquals(0,main.posts);
    }
    @Test public void resetClearsPendingAndOldEpochCannotEnterNewTurn() {
        Main main=new Main();List<byte[]> heard=new ArrayList<>();CarPcmIngress q=new CarPcmIngress(main,(p,r)->heard.add(p.clone()),(a,r)->fail());
        long old=open(q);byte[] oldP=pcm(640);q.offerOwned(old,oldP,16000);q.endAttempt();zero(oldP);
        long next=open(q);byte[] stale=pcm(640),current=pcm(640);assertNotEquals(old,next);
        assertEquals(CarPcmIngress.Offer.IGNORED,q.offerOwned(old,stale,16000));zero(stale);
        assertEquals(CarPcmIngress.Offer.ACCEPTED,q.offerOwned(next,current,16000));assertEquals(1,main.posts);
        main.all();assertEquals(1,heard.size());zero(current);
    }
    @Test public void readinessCloseAndReopenInvalidatesPendingWithoutNewAttempt() {
        Main main=new Main();AtomicInteger delivered=new AtomicInteger();CarPcmIngress q=new CarPcmIngress(main,(p,r)->delivered.incrementAndGet(),(a,r)->fail());
        long old=open(q);byte[] first=pcm(640);q.offerOwned(old,first,16000);q.setOpen(false);zero(first);
        q.setOpen(true);assertNotEquals(old,q.openEpoch());byte[] second=pcm(640);q.offerOwned(q.openEpoch(),second,16000);
        main.all();assertEquals(1,delivered.get());zero(second);
    }
    @Test public void supersededFailureCannotStopReplacement() {
        Main main=new Main();AtomicInteger failures=new AtomicInteger(),delivered=new AtomicInteger();
        CarPcmIngress q=new CarPcmIngress(main,(p,r)->delivered.incrementAndGet(),(a,r)->failures.incrementAndGet());
        long epoch=open(q);q.invalidAudio(epoch);q.endAttempt();long next=open(q);q.offerOwned(next,pcm(640),16000);main.all();
        assertEquals(0,failures.get());assertEquals(1,delivered.get());
    }
    @Test public void failureTokenIsCurrentOnlyUntilTurnEnds() {
        Main main=new Main();long[] reported={0};CarPcmIngress q=new CarPcmIngress(main,(p,r)->fail(),(a,r)->reported[0]=a);
        long attempt=q.beginAttempt();q.setOpen(true);q.invalidAudio(q.openEpoch());main.all();
        assertEquals(attempt,reported[0]);assertTrue(q.isFailedAttempt(attempt));q.endAttempt();assertFalse(q.isFailedAttempt(attempt));
    }
    @Test public void offMainCloseDetachesPendingButDoesNotMutateBorrowedUntilReturn() throws Exception {
        Main main=new Main();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        byte[] borrowed=pcm(640),pending=pcm(640);AtomicBoolean ack=new AtomicBoolean();
        CarPcmIngress q=new CarPcmIngress(main,(p,r)->{try { assertSame(borrowed,p);entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));assertEquals(7,p[0]); } catch(Throwable e){error.set(e);}},(a,r)->fail());
        long epoch=open(q);q.offerOwned(epoch,borrowed,16000);q.offerOwned(epoch,pending,16000);
        Thread fakeMain=new Thread(main::one,"test-main");fakeMain.start();assertTrue(entered.await(2,TimeUnit.SECONDS));
        q.close();zero(pending);assertEquals(7,borrowed[0]);assertEquals(640,q.retainedBytes());assertEquals(1,q.retainedChunks());
        main.post(()->ack.set(true));assertFalse(ack.get()); // A real Main UI ack stays behind the borrowed callback.
        byte[] late=pcm(640);assertEquals(CarPcmIngress.Offer.IGNORED,q.offerOwned(epoch,late,16000));zero(late);
        release.countDown();fakeMain.join(2000);assertFalse(fakeMain.isAlive());if(error.get()!=null)throw new AssertionError(error.get());
        zero(borrowed);assertEquals(0,q.retainedBytes());assertFalse(ack.get());main.all();assertTrue(ack.get());assertEquals(0,q.beginAttempt());
    }
    @Test public void borrowedCountsTowardCapDuringConcurrentOffer() throws Exception {
        Main main=new Main();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger failures=new AtomicInteger();byte[] borrowed=pcm(6400);
        CarPcmIngress q=new CarPcmIngress(main,(p,r)->{entered.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}},(a,r)->{assertEquals(CarPcmIngress.Failure.OVERFLOW,r);failures.incrementAndGet();});
        long epoch=open(q);q.offerOwned(epoch,borrowed,16000);Thread fakeMain=new Thread(main::one);fakeMain.start();assertTrue(entered.await(2,TimeUnit.SECONDS));
        byte[] extra=pcm(2);assertEquals(CarPcmIngress.Offer.FAILED,q.offerOwned(epoch,extra,16000));zero(extra);
        assertEquals(6400,q.retainedBytes());assertEquals(7,borrowed[0]);release.countDown();fakeMain.join(2000);main.all();
        zero(borrowed);assertEquals(1,failures.get());assertEquals(0,q.retainedBytes());
    }
    @Test public void sinkExceptionWipesAndReportsOnce() {
        Main main=new Main();List<CarPcmIngress.Failure> failures=new ArrayList<>();byte[] p=pcm(640),pending=pcm(640);
        CarPcmIngress q=new CarPcmIngress(main,(b,r)->{throw new IllegalStateException("synthetic");},(a,r)->failures.add(r));long epoch=open(q);
        q.offerOwned(epoch,p,16000);q.offerOwned(epoch,pending,16000);main.all();zero(p);zero(pending);
        assertEquals(Collections.singletonList(CarPcmIngress.Failure.SINK_FAILED),failures);assertEquals(0,q.retainedBytes());
    }
    @Test public void boundedBatchYieldsAndRepostsOnlyOneRunnable() {
        Main main=new Main();AtomicInteger count=new AtomicInteger();CarPcmIngress[] holder=new CarPcmIngress[1];
        holder[0]=new CarPcmIngress(main,(p,r)->{if(count.incrementAndGet()<25)holder[0].offerOwned(holder[0].openEpoch(),pcm(2),16000);},(a,r)->fail());
        holder[0].offerOwned(open(holder[0]),pcm(2),16000);main.one();assertEquals(10,count.get());assertEquals(1,main.size());
        main.all();assertEquals(25,count.get());assertEquals(3,main.posts);assertEquals(1,main.maxPending);
    }
    @Test public void schedulerRefusalWipesAndTerminallyClosesAdmission() {
        Main main=new Main();main.accept=false;CarPcmIngress q=new CarPcmIngress(main,(p,r)->fail(),(a,r)->fail());long epoch=open(q);byte[] p=pcm(640);
        assertEquals(CarPcmIngress.Offer.POST_FAILED,q.offerOwned(epoch,p,16000));zero(p);assertEquals(0,q.retainedBytes());assertEquals(0,q.beginAttempt());
        byte[] next=pcm(640);assertEquals(CarPcmIngress.Offer.IGNORED,q.offerOwned(epoch,next,16000));zero(next);assertEquals(1,main.posts);
    }
    @Test public void closeBeforeDrainPreventsAnyDeliveryAndCanRepeat() {
        Main main=new Main();CarPcmIngress q=new CarPcmIngress(main,(p,r)->fail(),(a,r)->fail());byte[] p=pcm(640);q.offerOwned(open(q),p,16000);
        q.close();q.close();zero(p);main.all();assertEquals(0,q.retainedChunks());assertEquals(0,q.openEpoch());
    }
}
