package org.openandroidauto.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CallAudioOutputGateTest {
    @Test fun `call and communication modes flush once and block producer admission`() {
        var writes = 0; var flushes = 0
        val gate = CallAudioOutputGate({ writes++; true }, { flushes++ })
        assertTrue(gate.write(byteArrayOf(1)))
        for (mode in 1..6) {
            assertEquals(mode == 1, gate.setMode(mode))
            assertFalse(gate.write(byteArrayOf(2)))
        }
        assertEquals(1, writes); assertEquals(1, flushes)
        assertTrue(gate.setMode(0))
        assertEquals(1, writes) // Releasing a call never starts a producer.
        assertTrue(gate.write(byteArrayOf(3)))
    }

    @Test fun `sentinel mode values do not fabricate a call and close cannot reopen`() {
        var flushes = 0
        val gate = CallAudioOutputGate({ true }, { flushes++ })
        assertFalse(gate.setMode(-2)); assertFalse(gate.setMode(-1))
        gate.close(); gate.close()
        assertEquals(1, flushes)
        assertFalse(gate.setMode(0)); assertFalse(gate.write(byteArrayOf(1)))
    }

    @Test fun `peer focus cannot override a call flush or a closed session`() {
        var granted = false
        val gate = CallAudioOutputGate({ true }, { granted = false })
        gate.applyFocus(true) { granted = it }; assertTrue(granted)
        gate.setMode(2); assertFalse(granted)
        gate.applyFocus(true) { granted = it }; assertFalse(granted)
        gate.setMode(0); assertFalse(granted) // No implicit regain after a call.
        gate.close(); gate.applyFocus(true) { granted = it }; assertFalse(granted)
    }

    @Test fun `call mute serializes with a focus response already being applied`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val muted = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gate = CallAudioOutputGate({ true }, { events.add("flush") })
        val response = thread { gate.applyFocus(true) { value ->
            entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); events.add("grant:$value")
        } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val muter = thread { gate.setMode(2); muted.countDown() }
        assertFalse(muted.await(30, TimeUnit.MILLISECONDS))
        release.countDown(); response.join(2000); muter.join(2000)
        assertFalse(response.isAlive); assertFalse(muter.isAlive)
        assertEquals(listOf("grant:true", "flush"), events)
        gate.applyFocus(true) { value -> assertFalse(value) }
    }

    @Test fun `mute flush follows any already admitted producer and excludes late writes`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val muted = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gate = CallAudioOutputGate({ entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); events.add("write"); true }, { events.add("flush") })
        val writer = thread { gate.write(byteArrayOf(1)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val muter = thread { gate.setMode(2); muted.countDown() }
        assertFalse(muted.await(30, TimeUnit.MILLISECONDS))
        release.countDown(); writer.join(2000); muter.join(2000)
        assertFalse(writer.isAlive); assertFalse(muter.isAlive)
        assertEquals(listOf("write", "flush"), events)
        assertFalse(gate.write(byteArrayOf(2)))
    }
}
