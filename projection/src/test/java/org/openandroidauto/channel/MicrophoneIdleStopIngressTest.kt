// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.channel

import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

/** Actual channel + actual ingress: an old stream STOP must not erase a new preparation. */
class MicrophoneIdleStopIngressTest {
    private class Fixture : VideoChannelCallback {
        val posted = ArrayDeque<Runnable>()
        val received = mutableListOf<ByteArray>()
        val wire = mutableListOf<ByteArray>()
        var stopped = 0
        val ingress = CarPcmIngress({ task -> posted.addLast(task); true },
            { pcm, rate -> assertEquals(16000, rate); received.add(pcm.clone()) },
            { _, reason -> fail("Unexpected ingress failure: $reason") })
        val channel = AudioInputChannel(7u, this,
            onStopped = { stopped++; ingress.endAttempt() },
            ownedReceiver = object : AudioInputChannel.OwnedPcmReceiver {
                override fun captureEpoch() = ingress.openEpoch()
                override fun invalid(epoch: Long) { ingress.invalidAudio(epoch) }
                override fun accept(epoch: Long, pcm: ByteArray, sampleRate: Int) { ingress.offerOwned(epoch, pcm, sampleRate) }
            })
        init { channel.onChannelOpened() }
        override fun onVideoFrame(channelId: UByte, payload: ByteArray) {}
        override fun onSendMessage(channelId: UByte, payload: ByteArray) { wire.add(payload) }
        fun prepare() { assertNotEquals(0L, ingress.beginAttempt()); assertEquals(0L, ingress.openEpoch()) }
        fun ready() { ingress.setOpen(true); assertNotEquals(0L, ingress.openEpoch()); channel.requestOpen(true) }
        fun ack(session: Int) { channel.onMessage(0x8006, byteArrayOf(8, session.toByte())) }
        fun pcm() { channel.onMessage(0, ByteArray(8) + byteArrayOf(1, 2, 3, 4)) }
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst().run() }
    }

    @Test fun idlePeerStopPreservesPreparingAttemptAndNewOpenDeliversPcm() {
        val f = Fixture(); f.prepare()
        f.channel.onMessage(0x8002, byteArrayOf())
        assertEquals(0, f.stopped)
        f.ready(); f.ack(1); f.pcm(); f.drain()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.received.single())
    }

    @Test fun oldClosingStopPreservesNewPreparationAndStillWaitsForBothResponses() {
        val f = Fixture(); f.prepare(); f.ready(); f.ack(1)
        f.ingress.endAttempt(); f.channel.requestOpen(false) // The old turn is closing.
        f.prepare()
        f.channel.onMessage(0x8002, byteArrayOf()) // Delayed old STOP while model loads.
        assertEquals(0, f.stopped)
        f.ready(); f.pcm(); f.drain(); assertTrue(f.received.isEmpty())
        f.ack(2) // The old CLOSE response permits a new OPEN, not new PCM yet.
        assertEquals(1, f.wire.last()[3].toInt())
        f.pcm(); f.drain(); assertTrue(f.received.isEmpty())
        f.ack(3); f.pcm(); f.drain()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.received.single())
    }

    @Test fun activelyRequestedStopStillEndsCurrentIngressAndWipesQueuedInput() {
        val f = Fixture(); f.prepare(); f.ready(); f.ack(1); f.pcm()
        assertEquals(4, f.ingress.retainedBytes())
        f.channel.onMessage(0x8002, byteArrayOf())
        assertEquals(1, f.stopped); assertEquals(0L, f.ingress.openEpoch()); assertEquals(0, f.ingress.retainedBytes())
        f.pcm(); f.drain(); assertTrue(f.received.isEmpty())
        f.prepare(); f.ready(); f.ack(2); f.pcm(); f.drain()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.received.single())
    }

    @Test fun terminalStopAlwaysEndsPreparingAttemptAndCannotReopen() {
        val f = Fixture(); f.prepare(); f.channel.stop()
        assertEquals(1, f.stopped); f.ingress.setOpen(true); assertEquals(0L, f.ingress.openEpoch())
        f.wire.clear(); f.channel.requestOpen(true); f.ack(1); f.pcm(); f.drain()
        assertTrue(f.wire.isEmpty()); assertTrue(f.received.isEmpty())
    }
}
