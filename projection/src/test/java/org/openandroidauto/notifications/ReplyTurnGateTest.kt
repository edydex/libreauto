package org.openandroidauto.notifications

import org.junit.Assert.*
import org.junit.Test

class ReplyTurnGateTest {
    @Test fun replyChangeThenDictateRejectsOldTranscriptWithoutConsumingNewPermit() {
        val gate = ReplyTurnGate()
        val a = gate.prepare(1)
        gate.cancel() // Select Reply on B; invalidate A immediately.
        val b = gate.prepare(2)
        assertNotEquals(a, b)
        assertFalse(gate.consume(a, 2)) // The callback carries A, never reads current B.
        assertEquals(b, gate.current(2))
        assertTrue(gate.consume(b, 2))
        assertFalse(gate.consume(b, 2))
    }

    @Test fun staleEndForAHasNoAuthorityToCancelB() {
        val gate = ReplyTurnGate()
        val a = gate.prepare(10)
        val b = gate.prepare(11)
        gate.cancel(a)
        assertEquals(b, gate.current(11))
        assertTrue(gate.consume(b, 11))
    }

    @Test fun repeatedRequestForSameTargetCoalescesOnlyUntilConsumed() {
        val gate = ReplyTurnGate()
        val token = gate.prepare(8)
        assertEquals(token, gate.prepare(8))
        assertTrue(gate.consume(token, 8))
        assertNotEquals(token, gate.prepare(8))
    }

    @Test fun targetGenerationMustMatchEvenWithCorrectToken() {
        val gate = ReplyTurnGate()
        val token = gate.prepare(20)
        assertNull(gate.current(21))
        assertFalse(gate.consume(token, 21))
        assertEquals(token, gate.current(20))
        gate.cancel(token)
        assertFalse(gate.consume(token, 20))
    }

    @Test fun stopInvalidatesTurnAndFreshPanelCannotReuseItsToken() {
        val old = ReplyTurnGate()
        val oldToken = old.prepare(1)
        old.cancel()
        assertFalse(old.consume(oldToken, 1))
        val replacement = ReplyTurnGate()
        val newToken = replacement.prepare(1)
        assertNotEquals(oldToken, newToken)
        assertFalse(replacement.consume(oldToken, 1))
        assertTrue(replacement.consume(newToken, 1))
    }
}
