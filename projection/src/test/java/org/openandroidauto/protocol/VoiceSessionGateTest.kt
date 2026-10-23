package org.openandroidauto.protocol
import org.junit.Assert.*
import org.junit.Test
class VoiceSessionGateTest {
    @Test fun `voice request remains pending until recorder is ready`() {val changes=mutableListOf<Boolean>();val gate=VoiceSessionGate{changes.add(it)};gate.request(true);assertTrue(gate.requested);assertFalse(gate.microphoneOpen);assertTrue(changes.isEmpty());gate.recordingReady(true);assertEquals(listOf(true),changes)}
    @Test fun `recording callback without a voice session cannot open mic`() {val changes=mutableListOf<Boolean>();val gate=VoiceSessionGate{changes.add(it)};gate.recordingReady(true);assertFalse(gate.microphoneOpen);assertTrue(changes.isEmpty())}
    @Test fun `explicit stop closes mic and ignores later readiness`() {val changes=mutableListOf<Boolean>();val gate=VoiceSessionGate{changes.add(it)};gate.request(true);gate.recordingReady(true);gate.request(false);gate.recordingReady(true);assertEquals(listOf(true,false),changes)}
    @Test fun `startup timeout closes pending request`() {val gate=VoiceSessionGate{};val token=gate.request(true);assertTrue(gate.expire(token));assertFalse(gate.requested);assertFalse(gate.microphoneOpen)}
    @Test fun `stale timeout cannot close next request`() {val gate=VoiceSessionGate{};val old=gate.request(true);gate.request(false);gate.request(true);assertFalse(gate.expire(old));assertTrue(gate.requested)}
    @Test fun `successful recording cancels initial timeout even during reply`() {val gate=VoiceSessionGate{};val token=gate.request(true);gate.recordingReady(true);gate.recordingReady(false);assertFalse(gate.expire(token));assertTrue(gate.requested);assertFalse(gate.microphoneOpen)}
    @Test fun `recording can reopen during an ongoing conversation`() {val changes=mutableListOf<Boolean>();val gate=VoiceSessionGate{changes.add(it)};gate.request(true);gate.recordingReady(true);gate.recordingReady(false);gate.recordingReady(true);assertEquals(listOf(true,false,true),changes)}
    @Test fun `new request never inherits old readiness`() {val gate=VoiceSessionGate{};gate.request(true);gate.recordingReady(true);gate.request(false);gate.request(true);assertFalse(gate.microphoneOpen);assertFalse(gate.hasOpened)}
    @Test fun `native call never opens AAP microphone or expires on its readiness timer`() {
        val changes = mutableListOf<Boolean>(); val gate = VoiceSessionGate { changes.add(it) }
        val token = gate.request(true, requiresMicrophone = false)
        gate.recordingReady(true)
        assertTrue(gate.requested); assertFalse(gate.microphoneOpen)
        assertFalse(gate.expire(token)); assertTrue(changes.isEmpty())
    }
    @Test fun `new explicit attempt invalidates old timeout and readiness`() {
        val gate = VoiceSessionGate {}; val previous = gate.request(true)
        gate.recordingReady(true)
        val current = gate.request(true, newAttempt = true)
        assertNotEquals(previous, current); assertFalse(gate.hasOpened); assertFalse(gate.microphoneOpen)
        assertFalse(gate.expire(previous)); assertTrue(gate.requested)
        assertTrue(gate.expire(current))
    }
    @Test fun `switching from routed microphone to native call closes the AAP route`() {
        val changes = mutableListOf<Boolean>(); val gate = VoiceSessionGate { changes.add(it) }
        gate.request(true); gate.recordingReady(true)
        gate.request(true, requiresMicrophone = false, newAttempt = true)
        gate.recordingReady(true)
        assertEquals(listOf(true, false), changes); assertFalse(gate.microphoneOpen)
    }

}
