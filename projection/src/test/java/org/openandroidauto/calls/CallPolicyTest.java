package org.openandroidauto.calls;

import org.junit.Test;
import static org.junit.Assert.*;

public class CallPolicyTest {
    private CallContact contact(long id,String name,String label,String number) {
        return new CallContact(id,42,"lookup-key",name,label,number);
    }
    @Test public void ordinaryNumbersRetainOnlyDialDigitsAndLeadingPlus() {
        assertEquals("+12025550123",CallPolicy.dialNumber("+1 (202) 555-0123"));
        assertEquals("2025550123",CallPolicy.dialNumber("202.555.0123"));
    }
    @Test public void rejectsPostDialServiceUriEmergencyAndNonAsciiInput() {
        for(String number:new String[]{"911","112","*#06#","2025550123,12","2025550123;12","2025550123p12",
            "tel:2025550123","2025550123@host","++12025550123","202+5550123","２０２５５５０１２３","2025550123\n", "1234567890123456"})
            assertNull(number,CallPolicy.dialNumber(number));
    }
    @Test public void matchesAllNameTermsAccentInsensitivelyOrExplicitNumber() {
        CallContact row=contact(1,"José River Lane","Mobile","+1 202 555 0123");
        assertTrue(CallPolicy.matches(row,"jose lane"));
        assertTrue(CallPolicy.matches(row,"555 0123"));
        assertFalse(CallPolicy.matches(row,"jose absent"));
        assertFalse(CallPolicy.matches(row,"mobile"));
    }
    @Test public void boundsQueryAndRejectsControlCharacters() {
        assertEquals("River Lane",CallPolicy.searchQuery("  River   Lane  "));
        assertNull(CallPolicy.searchQuery("x".repeat(81)));
        assertNull(CallPolicy.searchQuery("name\nother"));
    }
    @Test public void contactConfirmationRejectsAnyChangedVisibleOrIdentityField() {
        CallContact original=contact(1,"River","Mobile","2025550123");
        assertTrue(original.sameTarget(contact(1,"River","Mobile","2025550123")));
        assertFalse(original.sameTarget(contact(2,"River","Mobile","2025550123")));
        assertFalse(original.sameTarget(contact(1,"Other","Mobile","2025550123")));
        assertFalse(original.sameTarget(contact(1,"River","Work","2025550123")));
        assertFalse(original.sameTarget(contact(1,"River","Mobile","2025550199")));
        assertFalse(original.sameTarget(new CallContact(1,43,"lookup-key","River","Mobile","2025550123")));
        assertFalse(original.sameTarget(new CallContact(1,42,"another-key","River","Mobile","2025550123")));
    }
    @Test public void confirmationIsSingleUseAndRequiresActiveOptInAndPermissions() {
        CallContact row=contact(1,"River","Mobile","2025550123");
        CallConfirmationGate gate=new CallConfirmationGate();
        CallConfirmationGate.Ticket ticket=gate.select(row);
        assertTrue(gate.consume(ticket,row,true,true,true));
        assertFalse(gate.consume(ticket,row,true,true,true));
        assertFalse(gate.consume(gate.select(row),row,false,true,true));
        assertFalse(gate.consume(gate.select(row),row,true,false,true));
        assertFalse(gate.consume(gate.select(row),row,true,true,false));
    }
    @Test public void invalidationAndNewSelectionRejectOldAsyncRevalidation() {
        CallContact a=contact(1,"A","Mobile","2025550123"), b=contact(2,"B","Work","2025550199");
        CallConfirmationGate gate=new CallConfirmationGate();
        CallConfirmationGate.Ticket first=gate.select(a);gate.invalidate();
        assertFalse(gate.consume(first,a,true,true,true));
        first=gate.select(a);CallConfirmationGate.Ticket second=gate.select(b);
        assertFalse(gate.consume(first,a,true,true,true));
        assertTrue(gate.consume(second,b,true,true,true));
    }
    @Test public void changedContactConsumesConfirmationWithoutCallingNewNumber() {
        CallContact row=contact(1,"A","Mobile","2025550123");
        CallConfirmationGate gate=new CallConfirmationGate();CallConfirmationGate.Ticket ticket=gate.select(row);
        assertFalse(gate.consume(ticket,contact(1,"A","Mobile","2025550199"),true,true,true));
        assertFalse(gate.consume(ticket,row,true,true,true));
    }
    @Test public void queryGenerationRefusesEarlierProviderResults() {
        CallSearchState state=new CallSearchState();long first=state.changed();
        long second=state.changed();assertFalse(state.current(first));assertTrue(state.current(second));
    }
    @Test public void oldDictationCannotConsumeOrCancelANewSearchTurn() {
        CallSearchState state=new CallSearchState();long first=state.prepareDictation();
        long second=state.prepareDictation();assertNotEquals(first,second);
        assertFalse(state.consumeDictation(first));state.cancelDictation(first);
        assertTrue(state.consumeDictation(second));assertFalse(state.consumeDictation(second));
    }
    @Test public void manualQueryChangeAndStopInvalidateLateTranscript() {
        CallSearchState state=new CallSearchState();long first=state.prepareDictation();state.changed();
        assertFalse(state.consumeDictation(first));long second=state.prepareDictation();state.cancelDictation(null);
        assertFalse(state.consumeDictation(second));
    }
}
