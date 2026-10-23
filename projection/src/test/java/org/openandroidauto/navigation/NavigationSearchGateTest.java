package org.openandroidauto.navigation;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationSearchGateTest {
    @Test public void queryRequiresBoundedPlaceTextWithoutControlCharacters() {
        assertEquals("Test square",NavigationSearchGate.searchQuery("  Test square  "));
        assertNull(NavigationSearchGate.searchQuery("x"));
        assertNull(NavigationSearchGate.searchQuery("x".repeat(201)));
        assertNull(NavigationSearchGate.searchQuery("Test\nsquare"));
        assertNotNull(NavigationSearchGate.searchQuery("東京駅"));
    }
    @Test public void normalVoiceResultIsSingleUseAndCanOnlyBecomeCandidates() {
        NavigationSearchGate gate=new NavigationSearchGate();long token=gate.prepareDictation();
        assertTrue(gate.consumeDictation(token));assertFalse(gate.consumeDictation(token));
        NavigationSearchGate.Candidates candidates=gate.beginCandidates(false);
        assertTrue(gate.current(candidates));assertFalse(candidates.requireConfirmation);
        assertNull(gate.confirm(candidates,"Place",1,2));
    }
    @Test public void oldVoiceCancelCannotEraseANewerTurn() {
        NavigationSearchGate gate=new NavigationSearchGate();long first=gate.prepareDictation(),second=gate.prepareDictation();
        assertNotEquals(first,second);assertFalse(gate.consumeDictation(first));gate.cancelDictation(first);
        assertTrue(gate.consumeDictation(second));
    }
    @Test public void typingTabStopOrTargetChangeInvalidatesLateVoiceAndResults() {
        NavigationSearchGate gate=new NavigationSearchGate();long voice=gate.prepareDictation();gate.invalidate();
        assertFalse(gate.consumeDictation(voice));
        NavigationSearchGate.Candidates first=gate.beginCandidates(false),second=gate.beginCandidates(false);
        assertFalse(gate.current(first));assertTrue(gate.current(second));gate.invalidate();assertFalse(gate.current(second));
    }
    @Test public void commandSearchRequiresSelectedCandidateAndExplicitSingleUseConfirmation() {
        NavigationSearchGate gate=new NavigationSearchGate();NavigationSearchGate.Candidates candidates=gate.beginCandidates(true);
        assertTrue(gate.hasCancelableFlow());
        assertTrue(candidates.requireConfirmation);
        assertFalse(gate.consume(null));
        NavigationSearchGate.Confirmation selection=gate.confirm(candidates,"Place",1,2);
        assertEquals("Place",selection.name);assertEquals(1,selection.latitude,0);assertEquals(2,selection.longitude,0);
        assertTrue(gate.consume(selection));assertFalse(gate.consume(selection));assertFalse(gate.current(candidates));assertFalse(gate.hasCancelableFlow());
    }
    @Test public void newSearchOrTypingRefusesOldDestinationConfirmation() {
        NavigationSearchGate gate=new NavigationSearchGate();NavigationSearchGate.Candidates first=gate.beginCandidates(true);
        NavigationSearchGate.Confirmation selection=gate.confirm(first,"Place",1,2);
        gate.beginCandidates(true);assertFalse(gate.consume(selection));assertNull(gate.confirm(first,"Place",1,2));
    }
    @Test public void selectingAnotherCandidateRefusesPreviousConfirmation() {
        NavigationSearchGate gate=new NavigationSearchGate();NavigationSearchGate.Candidates candidates=gate.beginCandidates(true);
        NavigationSearchGate.Confirmation first=gate.confirm(candidates,"First",1,2),second=gate.confirm(candidates,"Second",3,4);
        assertFalse(gate.consume(first));assertTrue(gate.consume(second));
    }
    @Test public void invalidDestinationNeverCreatesAConfirmation() {
        NavigationSearchGate gate=new NavigationSearchGate();NavigationSearchGate.Candidates candidates=gate.beginCandidates(true);
        assertNull(gate.confirm(candidates,"",1,2));assertNull(gate.confirm(candidates,"Place",Double.NaN,2));
        assertNull(gate.confirm(candidates,"Place",91,2));assertNull(gate.confirm(candidates,"Place",1,181));
        assertFalse(gate.consume(null));
    }
    @Test public void finishingOldDictationDoesNotEraseItsNewSearchCandidates() {
        NavigationSearchGate gate=new NavigationSearchGate();long token=gate.prepareDictation();assertTrue(gate.consumeDictation(token));
        NavigationSearchGate.Candidates candidates=gate.beginCandidates(false);gate.cancelDictation(token);
        assertTrue(gate.current(candidates));
    }
    @Test public void cancellationCapabilityTracksOnlyVoiceOrExplicitCommandFlow() {
        NavigationSearchGate gate=new NavigationSearchGate();assertFalse(gate.hasCancelableFlow());
        long voice=gate.prepareDictation();assertTrue(gate.hasCancelableFlow());gate.cancelDictation(voice);assertFalse(gate.hasCancelableFlow());
        gate.beginCandidates(false);assertFalse(gate.hasCancelableFlow());gate.beginCandidates(true);assertTrue(gate.hasCancelableFlow());
        gate.invalidate();assertFalse(gate.hasCancelableFlow());
    }
}
