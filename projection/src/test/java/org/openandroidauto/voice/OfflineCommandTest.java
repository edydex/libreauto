// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.voice;
import org.junit.Test;
import static org.junit.Assert.*;
public class OfflineCommandTest {
 @Test public void explicitPrefixesKeepNamesAndPlacesAsQueriesOnly(){
  assertCommand(" Call Alex Example ",OfflineCommand.Kind.CALL,"Alex Example");
  assertCommand("Directions to Seattle Central Library",OfflineCommand.Kind.DIRECTIONS,"Seattle Central Library");
  assertCommand("navigate to 100 Main Street",OfflineCommand.Kind.DIRECTIONS,"100 Main Street");
 }
 @Test public void messagingNeverInterpretsBodyOrSend(){
  assertCommand("reply to Alex",OfflineCommand.Kind.REPLY,"Alex");
  assertCommand("message Alex Example",OfflineCommand.Kind.MESSAGE,"Alex Example");
  assertCommand("text Alex",OfflineCommand.Kind.MESSAGE,"Alex");
  assertEquals(OfflineCommand.Kind.UNKNOWN,OfflineCommand.parse("send").kind);
  assertEquals(OfflineCommand.Kind.UNKNOWN,OfflineCommand.parse("call").kind);
 }
 @Test public void currentBodyTextHasNoCommandSideEffects(){
  // A grammar result contains only a kind/query; no receiver, gateway, phone or route API.
  OfflineCommand result=OfflineCommand.parse("message Alex saying call me");
  assertEquals(OfflineCommand.Kind.MESSAGE,result.kind);assertEquals("Alex saying call me",result.query);
 }
 @Test public void boundsControlsAndEmptyInputsRefuse(){
  for(String bad:new String[]{"", "  ","Call ","call A\nmessage B","call A\u0000B","call "+"a".repeat(121),"a".repeat(241)})
   assertEquals(OfflineCommand.Kind.UNKNOWN,OfflineCommand.parse(bad).kind);
  assertEquals(OfflineCommand.Kind.UNKNOWN,OfflineCommand.parse(null).kind);
 }
 @Test public void noEmbeddedOrPartialTrigger(){
  for(String bad:new String[]{"please call Alex","recall Alex","callback Alex","I will message Alex","start directions to Seattle"})
   assertEquals(OfflineCommand.Kind.UNKNOWN,OfflineCommand.parse(bad).kind);
 }
 @Test public void caseWhitespaceAndCancelAreBounded(){
  assertCommand("CALL   Alex   Example",OfflineCommand.Kind.CALL,"Alex Example");
  assertCommand("cancel",OfflineCommand.Kind.CANCEL,"");assertCommand("never mind",OfflineCommand.Kind.CANCEL,"");
 }
 private void assertCommand(String text,OfflineCommand.Kind kind,String query){OfflineCommand c=OfflineCommand.parse(text);assertEquals(kind,c.kind);assertEquals(query,c.query);}
}
