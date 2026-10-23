// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.channel

import org.junit.Assert.*
import org.junit.Test

class CarPcmInputBoundsTest {
    private class Wire : VideoChannelCallback {
        val sent=mutableListOf<ByteArray>()
        override fun onVideoFrame(channelId: UByte,payload: ByteArray) {}
        override fun onSendMessage(channelId: UByte,payload: ByteArray) { sent.add(payload) }
    }
    private class Owned : AudioInputChannel.OwnedPcmReceiver {
        var captures=0;var invalid=0;var accepted=0;var token=73L;var actualToken=0L;var pcm:ByteArray?=null
        override fun captureEpoch():Long { captures++;return token }
        override fun invalid(epoch:Long) { assertEquals(token,epoch);invalid++ }
        override fun accept(epoch:Long,pcm:ByteArray,sampleRate:Int) {
            actualToken=epoch;this.pcm=pcm;accepted++;assertEquals(16000,sampleRate)
        }
    }
    private fun ready(wire:Wire,owned:AudioInputChannel.OwnedPcmReceiver):AudioInputChannel =
        AudioInputChannel(5u,wire,ownedReceiver=owned).also {
            it.onChannelOpened();it.requestOpen(true)
            it.onMessage(0x8006,byteArrayOf(8,7,16,5));wire.sent.clear()
        }
    private fun ack(wire:Wire) { assertArrayEquals(byteArrayOf(0x80.toByte(),4,8,7,16,1),wire.sent.single()) }
    @Test fun maximumPacketCopiesOnceWithCapturedEpochAndTimestampRemoved() {
        val wire=Wire();val owned=Owned();val channel=ready(wire,owned);val payload=ByteArray(6408){7}
        channel.onMessage(AVMessageType.AV_MEDIA_WITH_TIMESTAMP,payload)
        assertEquals(1,owned.captures);assertEquals(1,owned.accepted);assertEquals(0,owned.invalid)
        assertEquals(73L,owned.actualToken);assertEquals(6400,owned.pcm!!.size);assertNotSame(payload,owned.pcm)
        ack(wire);owned.pcm!!.fill(0);assertEquals(7,payload[8].toInt())
    }
    @Test fun oversizedOddEmptyAndTruncatedTimestampAreRefusedBeforePcmCopy() {
        for((type,length) in listOf(AVMessageType.AV_MEDIA to 6402,AVMessageType.AV_MEDIA to 3,
            AVMessageType.AV_MEDIA to 0,AVMessageType.AV_MEDIA_WITH_TIMESTAMP to 7,
            AVMessageType.AV_MEDIA_WITH_TIMESTAMP to 8,AVMessageType.AV_MEDIA_WITH_TIMESTAMP to 6409)) {
            val wire=Wire();val owned=Owned();val channel=ready(wire,owned)
            channel.onMessage(type,ByteArray(length))
            assertEquals(1,owned.captures);assertEquals(1,owned.invalid);assertEquals(0,owned.accepted);assertNull(owned.pcm);ack(wire)
        }
    }
    @Test fun closedInputDoesNotFailCurrentTurnOrAllocatePcmForMalformedPacket() {
        val wire=Wire();val owned=Owned();val channel=ready(wire,owned);channel.requestOpen(false);wire.sent.clear()
        channel.onMessage(AVMessageType.AV_MEDIA,ByteArray(3))
        assertEquals(0,owned.accepted);assertEquals(0,owned.invalid);assertTrue(wire.sent.isEmpty())
    }
    @Test fun peerAndTerminalStopInvalidateMailboxBeforePreviouslyQueuedDelivery() {
        for(terminal in listOf(false,true)) {
            val posted=ArrayDeque<Runnable>();val wire=Wire();var delivered=0;var stopped=0
            val queue=CarPcmIngress({ posted.add(it);true },{ _,_->delivered++ },{ _,_->fail() })
            queue.beginAttempt();queue.setOpen(true)
            val receiver=object:AudioInputChannel.OwnedPcmReceiver {
                override fun captureEpoch()=queue.openEpoch()
                override fun invalid(epoch:Long) { queue.invalidAudio(epoch) }
                override fun accept(epoch:Long,pcm:ByteArray,sampleRate:Int) { queue.offerOwned(epoch,pcm,sampleRate) }
            }
            val channel=AudioInputChannel(5u,wire,onStopped={stopped++;queue.endAttempt()},ownedReceiver=receiver)
            channel.onChannelOpened();channel.requestOpen(true);channel.onMessage(0x8006,byteArrayOf(8,7))
            channel.onMessage(AVMessageType.AV_MEDIA,ByteArray(640){9})
            assertEquals(640,queue.retainedBytes())
            if(terminal)channel.stop() else channel.onMessage(AVMessageType.STOP_INDICATION,ByteArray(0))
            assertEquals(1,stopped);assertEquals(0,queue.retainedBytes());posted.removeFirst().run();assertEquals(0,delivered)
        }
    }
    @Test fun epochCapturedBeforeCopyCannotEnterReplacementTurn() {
        val posted=ArrayDeque<Runnable>();val queue=CarPcmIngress({posted.add(it);true},{_,_->fail()},{_,_->fail()})
        queue.beginAttempt();queue.setOpen(true);val old=queue.openEpoch();var accepted:ByteArray?=null
        val receiver=object:AudioInputChannel.OwnedPcmReceiver {
            override fun captureEpoch():Long { val token=queue.openEpoch();queue.endAttempt();queue.beginAttempt();queue.setOpen(true);return token }
            override fun invalid(epoch:Long) { fail() }
            override fun accept(epoch:Long,pcm:ByteArray,sampleRate:Int) { assertEquals(old,epoch);accepted=pcm;assertEquals(CarPcmIngress.Offer.IGNORED,queue.offerOwned(epoch,pcm,sampleRate)) }
        }
        val channel=ready(Wire(),receiver);channel.onMessage(AVMessageType.AV_MEDIA,ByteArray(640){9})
        assertArrayEquals(ByteArray(640),accepted);assertEquals(0,posted.size);assertEquals(0,queue.retainedBytes())
    }
    @Test fun malformedPacketFromOldEpochCannotFailReplacementTurn() {
        val posted=ArrayDeque<Runnable>();val queue=CarPcmIngress({posted.add(it);true},{_,_->fail()},{_,_->fail()})
        queue.beginAttempt();queue.setOpen(true)
        val receiver=object:AudioInputChannel.OwnedPcmReceiver {
            override fun captureEpoch():Long { val token=queue.openEpoch();queue.endAttempt();queue.beginAttempt();queue.setOpen(true);return token }
            override fun invalid(epoch:Long) { assertEquals(CarPcmIngress.Offer.IGNORED,queue.invalidAudio(epoch)) }
            override fun accept(epoch:Long,pcm:ByteArray,sampleRate:Int) { fail() }
        }
        ready(Wire(),receiver).onMessage(AVMessageType.AV_MEDIA,ByteArray(3))
        assertNotEquals(0,queue.openEpoch());assertEquals(0,posted.size)
    }
    private fun totals(channel:AudioInputChannel):Pair<Long,Long> {
        val flags=channel.diagnosticFlags().split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        return flags.getValue("mic_pcm_packets").toLong() to flags.getValue("mic_pcm_bytes").toLong()
    }
    @Test fun admittedPacketTotalsExcludeTimestampAndExistBeforeReceiverDelivery() {
        val wire=Wire();lateinit var channel:AudioInputChannel;val observed=mutableListOf<Pair<Long,Long>>()
        val receiver=object:AudioInputChannel.OwnedPcmReceiver {
            override fun captureEpoch()=1L
            override fun invalid(epoch:Long) { fail() }
            override fun accept(epoch:Long,pcm:ByteArray,sampleRate:Int) {
                observed.add(totals(channel));pcm.fill(0)
            }
        }
        channel=ready(wire,receiver)
        assertEquals(0L to 0L,totals(channel))
        channel.onMessage(AVMessageType.AV_MEDIA,ByteArray(640))
        channel.onMessage(AVMessageType.AV_MEDIA_WITH_TIMESTAMP,ByteArray(1288))
        assertEquals(listOf(1L to 640L,2L to 1920L),observed)
        assertEquals(2L to 1920L,totals(channel))
    }
    @Test fun invalidPcmDoesNotIncrementAdmittedTotals() {
        val owned=Owned();val channel=ready(Wire(),owned)
        for((type,size) in listOf(AVMessageType.AV_MEDIA to 0,AVMessageType.AV_MEDIA to 3,
            AVMessageType.AV_MEDIA to 6402,AVMessageType.AV_MEDIA_WITH_TIMESTAMP to 7,
            AVMessageType.AV_MEDIA_WITH_TIMESTAMP to 8))channel.onMessage(type,ByteArray(size))
        assertEquals(0L to 0L,totals(channel));assertEquals(0,owned.accepted);assertEquals(5,owned.invalid)
    }
    @Test fun noSessionClosedAndTerminalPacketsLeaveCumulativeTotalsUnchanged() {
        val wire=Wire();val owned=Owned();val channel=AudioInputChannel(5u,wire,ownedReceiver=owned)
        channel.onMessage(AVMessageType.AV_MEDIA,ByteArray(640))
        channel.onChannelOpened();channel.requestOpen(true)
        channel.onMessage(AVMessageType.AV_MEDIA,ByteArray(640))
        assertEquals(0L to 0L,totals(channel))
        channel.onMessage(0x8006,byteArrayOf(8,7))
        channel.onMessage(AVMessageType.AV_MEDIA,ByteArray(640))
        channel.requestOpen(false);channel.onMessage(AVMessageType.AV_MEDIA,ByteArray(640))
        channel.stop();channel.onMessage(AVMessageType.AV_MEDIA,ByteArray(640))
        assertEquals(1L to 640L,totals(channel));assertEquals(1,owned.accepted)
        owned.pcm!!.fill(0)
    }
}
