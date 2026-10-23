// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.sms;
import org.junit.Test;
import org.openandroidauto.calls.CallContact;
import java.util.ArrayList;
import static org.junit.Assert.*;
public class SmsSendGateTest {
 private final CallContact alex=new CallContact(1,2,"lookup-a","Alex Example","Mobile","+12025550123");
 private final CallContact other=new CallContact(3,4,"lookup-b","Other Example","Mobile","+12025550124");
 private static final class Fake implements SmsSendGate.Gateway {
  final ArrayList<SmsSendGate.Dispatch> sent=new ArrayList<>();public void send(SmsSendGate.Dispatch d){sent.add(d);}
 }
 private SmsSendGate.Ticket ready(SmsSendGate gate){SmsSendGate.Ticket t=gate.select(alex,7);assertTrue(gate.confirmRecipient(t,alex,7));gate.setDraft(t,"Neutral fixture");return t;}
 @Test public void onlyExplicitConfirmedFreshSendReachesFakeOnce(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=ready(gate);Fake fake=new Fake();
  assertEquals(0,fake.sent.size());assertTrue(gate.send(t,alex,"Neutral fixture",7,true,true,true,fake));
  assertEquals(1,fake.sent.size());assertEquals(alex.dialNumber,fake.sent.get(0).number);assertEquals("Neutral fixture",fake.sent.get(0).body);assertEquals(7,fake.sent.get(0).subscriptionId);
  assertFalse(gate.send(t,alex,"Neutral fixture",7,true,true,true,fake));assertEquals(1,fake.sent.size());
 }
 @Test public void selectingOrDictatingNeverSendsAndRecipientNeedsConfirm(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=gate.select(alex,7);Fake fake=new Fake();gate.setDraft(t,"ignored");
  assertFalse(gate.send(t,alex,"ignored",7,true,true,true,fake));assertTrue(fake.sent.isEmpty());
 }
 @Test public void changedContactOrNumberRefuses(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=ready(gate);Fake fake=new Fake();
  assertFalse(gate.send(t,other,"Neutral fixture",7,true,true,true,fake));assertTrue(fake.sent.isEmpty());
 }
 @Test public void sameNumberWithChangedVisibleIdentityRefuses(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=ready(gate);Fake fake=new Fake();
  CallContact changed=new CallContact(1,2,"lookup-a","Renamed Example","Mobile",alex.number);
  assertFalse(gate.send(t,changed,"Neutral fixture",7,true,true,true,fake));assertTrue(fake.sent.isEmpty());
 }
 @Test public void defaultSmsSimChangeNeverSilentlyUsesNewSim(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=ready(gate);Fake fake=new Fake();
  assertFalse(gate.send(t,alex,"Neutral fixture",8,true,true,true,fake));assertTrue(fake.sent.isEmpty());
  assertNull(gate.select(alex,-1));
  assertNull(gate.select(alex,Integer.MAX_VALUE));
 }
 @Test public void recipientConfirmationRequiresFreshProviderAndSameSim(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=gate.select(alex,7);
  assertFalse(gate.confirmRecipient(t,other,7));assertFalse(gate.confirmed(t));
  t=gate.select(alex,7);assertFalse(gate.confirmRecipient(t,alex,8));assertFalse(gate.confirmed(t));
 }
 @Test public void editedBodyAfterSendClickRequiresNewReview(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=ready(gate);Fake fake=new Fake();gate.setDraft(t,"Replacement");
  assertFalse(gate.send(t,alex,"Neutral fixture",7,true,true,true,fake));assertTrue(fake.sent.isEmpty());
 }
 @Test public void eachMissingAuthorityRefuses(){
  for(int missing=0;missing<3;missing++) {SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=ready(gate);Fake fake=new Fake();
   assertFalse(gate.send(t,alex,"Neutral fixture",7,missing!=0,missing!=1,missing!=2,fake));assertTrue(fake.sent.isEmpty());}
 }
 @Test public void cancelAndReplacementCannotSendOldRecipient(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket old=ready(gate);gate.invalidate();SmsSendGate.Ticket next=gate.select(other,7);Fake fake=new Fake();
  assertFalse(gate.send(old,alex,"Neutral fixture",7,true,true,true,fake));assertTrue(gate.current(next));assertTrue(fake.sent.isEmpty());
 }
 @Test public void invalidBodiesRefuse(){
  for(String value:new String[]{"", "  ","x".repeat(1001),"bad\u0000"}) {SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=ready(gate);gate.setDraft(t,value);Fake fake=new Fake();
   assertFalse(gate.send(t,alex,value,7,true,true,true,fake));assertTrue(fake.sent.isEmpty());}
 }
 @Test public void failedGatewayIsStillConsumedNoAutomaticRetry(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=ready(gate);
  try{gate.send(t,alex,"Neutral fixture",7,true,true,true,d->{throw new IllegalStateException("fixture");});fail();}catch(IllegalStateException expected){}
  Fake fake=new Fake();assertFalse(gate.send(t,alex,"Neutral fixture",7,true,true,true,fake));assertTrue(fake.sent.isEmpty());
 }
 @Test public void gatewayCallRunsOutsideGateMonitor(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket t=ready(gate);
  assertTrue(gate.send(t,alex,"Neutral fixture",7,true,true,true,d->assertFalse(Thread.holdsLock(gate))));
 }
 @Test public void staleConfirmationCannotEraseReplacementRecipient(){
  SmsSendGate gate=new SmsSendGate();SmsSendGate.Ticket old=gate.select(alex,7);SmsSendGate.Ticket next=gate.select(other,7);
  assertFalse(gate.confirmRecipient(old,alex,7));assertTrue(gate.current(next));assertTrue(gate.confirmRecipient(next,other,7));
 }
}
