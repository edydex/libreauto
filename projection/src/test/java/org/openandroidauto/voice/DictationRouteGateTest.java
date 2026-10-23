package org.openandroidauto.voice;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.openandroidauto.voice.DictationRouteGate.Purpose.*;
public class DictationRouteGateTest {
 @Test public void equalPanelTokensCannotCrossMessageAndContactPurposes() {
  DictationRouteGate gate=new DictationRouteGate();
  DictationRouteGate.Request message=gate.begin(MESSAGE_REPLY,1);assertNotNull(message);
  assertNull(gate.consume(message.token,CONTACT_SEARCH));assertSame(message,gate.consume(message.token,MESSAGE_REPLY));assertNull(gate.consume(message.token,MESSAGE_REPLY));
  gate.cancel();DictationRouteGate.Request call=gate.begin(CONTACT_SEARCH,1);
  assertNotEquals(message.token,call.token);assertNull(gate.consume(message.token,CONTACT_SEARCH));assertSame(call,gate.consume(call.token,CONTACT_SEARCH));
 }
 @Test public void lateMessageAfterTabChangeCannotBecomeContactSearch() {
  DictationRouteGate gate=new DictationRouteGate();DictationRouteGate.Request old=gate.begin(MESSAGE_REPLY,7);
  assertSame(old,gate.cancel());DictationRouteGate.Request next=gate.begin(CONTACT_SEARCH,7);
  assertTrue(next.generation>old.generation);assertNull(gate.consume(old.token,CONTACT_SEARCH));assertSame(next,gate.consume(next.token,CONTACT_SEARCH));
 }
 @Test public void lateContactSearchCannotEnterReplacementMessageDraft() {
  DictationRouteGate gate=new DictationRouteGate();DictationRouteGate.Request search=gate.begin(CONTACT_SEARCH,9);
  gate.cancel();DictationRouteGate.Request reply=gate.begin(MESSAGE_REPLY,10);
  assertNull(gate.consume(search.token,MESSAGE_REPLY));assertFalse(gate.owns(CONTACT_SEARCH,9));assertTrue(gate.owns(MESSAGE_REPLY,10));assertSame(reply,gate.consume(reply.token,MESSAGE_REPLY));
 }
 @Test public void stalePanelCancellationCannotMatchReplacementPurposeWithSameToken() {
  DictationRouteGate gate=new DictationRouteGate();DictationRouteGate.Request old=gate.begin(MESSAGE_REPLY,20);
  gate.cancel();DictationRouteGate.Request search=gate.begin(CONTACT_SEARCH,20);
  assertFalse(gate.owns(MESSAGE_REPLY,old.targetToken));assertTrue(gate.owns(CONTACT_SEARCH,search.targetToken));
  assertSame(search,gate.consume(search.token,CONTACT_SEARCH));
 }
 @Test public void noReplacementOrZeroTokenAndNoRepeatDelivery() {
  DictationRouteGate gate=new DictationRouteGate();assertNull(gate.begin(CONTACT_SEARCH,0));
  DictationRouteGate.Request owned=gate.begin(CONTACT_SEARCH,30);assertNull(gate.begin(MESSAGE_REPLY,31));
  assertTrue(gate.canReportStatus());assertSame(owned,gate.consume(owned.token,CONTACT_SEARCH));assertFalse(gate.canReportStatus());assertNull(gate.consume(owned.token,CONTACT_SEARCH));
 }
 @Test public void separateDashboardsNeverShareRouteTokens() {
  DictationRouteGate.Request a=new DictationRouteGate().begin(MESSAGE_REPLY,1);
  DictationRouteGate.Request b=new DictationRouteGate().begin(MESSAGE_REPLY,1);assertNotEquals(a.token,b.token);
 }
 @Test public void commandCannotTurnIntoMapSearchOrBodyAfterTabCancellation() {
  DictationRouteGate gate=new DictationRouteGate();DictationRouteGate.Request command=gate.begin(COMMAND,1);
  gate.cancel();DictationRouteGate.Request map=gate.begin(MAP_PLACE_SEARCH,1);
  assertNull(gate.consume(command.token,MAP_PLACE_SEARCH));assertSame(map,gate.consume(map.token,MAP_PLACE_SEARCH));
 }
 @Test public void rawMapDictationCannotBeParsedAsGlobalCommand() {
  DictationRouteGate gate=new DictationRouteGate();DictationRouteGate.Request map=gate.begin(MAP_PLACE_SEARCH,2);
  assertNull(gate.consume(map.token,COMMAND));assertSame(map,gate.consume(map.token,MAP_PLACE_SEARCH));
 }
 @Test public void bodyRouteKeepsCommandWordsAsBodyOnly() {
  DictationRouteGate gate=new DictationRouteGate();DictationRouteGate.Request body=gate.begin(MESSAGE_REPLY,3);
  assertNull(gate.consume(body.token,COMMAND));assertSame(body,gate.consume(body.token,MESSAGE_REPLY));
 }
}
