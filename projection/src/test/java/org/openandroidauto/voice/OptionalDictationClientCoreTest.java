// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.voice;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
public class OptionalDictationClientCoreTest {
 @Test public void queueCopiesBorrowedAudioAndWipesPendingOnCancel() throws Exception {
  DictationPcmQueue q=new DictationPcmQueue(); byte[] borrowed={1,2};
  assertEquals(DictationPcmQueue.Offer.NOT_READY,q.offer(borrowed,16000));assertTrue(q.ready());
  assertEquals(DictationPcmQueue.Offer.ACCEPTED,q.offer(borrowed,16000)); Arrays.fill(borrowed,(byte)0);
  byte[] owned=q.take();assertArrayEquals(new byte[]{1,2},owned); q.close();
  assertArrayEquals(new byte[]{1,2},owned); // Borrowed IPC chunk is wiped by its owner only after transact returns.
  assertNull(q.take());assertFalse(q.ready());assertEquals(DictationPcmQueue.Offer.CLOSED,q.offer(new byte[2],16000));
 }
 @Test public void totalBytesAndChunkCountStayBounded() throws Exception {
  DictationPcmQueue q=new DictationPcmQueue();q.ready();
  for(int i=0;i<10;i++) assertEquals(DictationPcmQueue.Offer.ACCEPTED,q.offer(new byte[640],16000));
  assertEquals(6400,q.queuedBytes());assertEquals(DictationPcmQueue.Offer.OVERFLOW,q.offer(new byte[2],16000));q.close();assertEquals(0,q.queuedBytes());
  DictationPcmQueue tiny=new DictationPcmQueue();tiny.ready();
  for(int i=0;i<10;i++) assertEquals(DictationPcmQueue.Offer.ACCEPTED,tiny.offer(new byte[2],16000));
  assertEquals(DictationPcmQueue.Offer.OVERFLOW,tiny.offer(new byte[2],16000));tiny.close();
 }
 @Test public void cancellationWakesWaitingIpcWithoutReopening() throws Exception {
  DictationPcmQueue q=new DictationPcmQueue();q.ready();
  java.util.concurrent.atomic.AtomicReference<byte[]> result=new java.util.concurrent.atomic.AtomicReference<>(new byte[1]);
  Thread waiter=new Thread(()->{try{result.set(q.take());}catch(InterruptedException e){throw new AssertionError(e);}});
  waiter.start();q.close();waiter.join(1000);assertFalse(waiter.isAlive());assertNull(result.get());assertFalse(q.ready());
 }
 @Test public void transcriptRequiresActualReadyThenInputCloseOnce() {
  DictationResultGate gate=new DictationResultGate(101);assertEquals(0,gate.claimTranscript());
  gate.ready();assertEquals(0,gate.claimTranscript());gate.inputClosed();assertEquals(101,gate.claimTranscript());assertEquals(0,gate.claimTranscript());
 }
 @Test public void lateAResultCannotBecomeNewBOwner() {
  DictationResultGate a=new DictationResultGate(101);a.ready();a.cancel();
  DictationResultGate b=new DictationResultGate(202);b.ready();b.inputClosed();
  assertEquals(0,a.claimTranscript());assertEquals(202,b.claimTranscript());
  a.ready();a.inputClosed();assertEquals(0,a.claimTranscript());
 }
 @Test public void noReadinessAfterClosedOrCancelAndNoZeroOwner() {
  DictationResultGate closed=new DictationResultGate(303);closed.inputClosed();closed.ready();assertEquals(0,closed.claimTranscript());
  try {new DictationResultGate(0);fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void cancellationBeforeRemoteStartCannotStartNativeWork() {
  DictationResultGate gate=new DictationResultGate(404);gate.cancel();assertFalse(gate.claimRemoteStart());
 }
 @Test public void remoteStartIsReservedOnceAndLaterCancelStillRevokesResult() {
  DictationResultGate gate=new DictationResultGate(505);assertTrue(gate.claimRemoteStart());assertFalse(gate.claimRemoteStart());
  gate.ready();gate.cancel();gate.inputClosed();assertEquals(0,gate.claimTranscript());
 }
 @Test public void naturalEndpointConsumesLateBorrowedInputWithoutCancellingFinal() throws Exception {
  DictationResultGate gate=new DictationResultGate(606);DictationPcmQueue queue=new DictationPcmQueue();
  assertFalse(gate.canDiscardEndedInput());gate.ready();queue.ready();assertFalse(gate.canDiscardEndedInput());
  gate.inputClosed();queue.close();assertEquals(DictationPcmQueue.Offer.CLOSED,queue.offer(new byte[640],16000));
  assertTrue(gate.canDiscardEndedInput());assertEquals(606,gate.claimTranscript());
  assertTrue(gate.canDiscardEndedInput());gate.cancel();assertFalse(gate.canDiscardEndedInput());
 }
 @Test public void unreadyFailureCannotPretendToConsumeClosedInput() {
  DictationResultGate gate=new DictationResultGate(707);gate.inputClosed();assertFalse(gate.canDiscardEndedInput());assertEquals(0,gate.claimTranscript());
 }
}
